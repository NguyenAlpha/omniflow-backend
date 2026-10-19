#!/usr/bin/env bash
# Deploy bản demo (có seed, không Redis) lên Azure App Service (Web App Java 25, plan B1)
# + PostgreSQL Flexible Server. Chạy bằng Git Bash.
#
#   az login                                   # một lần
#   ./scripts/azure-deploy.sh                  # lần đầu: tạo hạ tầng + deploy; lần sau: build + deploy lại
#   FRONTEND_ORIGIN=https://<app>.vercel.app ./scripts/azure-deploy.sh   # đổi CORS
#
# Secret (mật khẩu DB, JWT, admin) được sinh lần đầu và lưu ở api/.azure-demo.env (gitignored) —
# chạy lại script dùng lại đúng các giá trị đó. Mất file này = mất mật khẩu admin/DB.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: không đổi "/home/..." thành đường dẫn Windows
command -v az >/dev/null || PATH="/c/Program Files/Microsoft SDKs/Azure/CLI2/wbin:$PATH"

cd "$(dirname "$0")/.."     # thư mục api

# Azure for Students chỉ cho phép: eastasia, indonesiacentral, malaysiawest, japaneast, koreacentral
LOCATION="${LOCATION:-eastasia}"
RG="${RG:-rg-quicktech-pos}"
PLAN=quiktech-pos-plan
DB_NAME=quiktech_pos
DB_ADMIN=quiktechadmin
JAR=target/quiktech-pos-0.0.1-SNAPSHOT.jar
FRONTEND_ORIGIN="${FRONTEND_ORIGIN:-}"

az account show --query "{subscription:name, user:user.name}" -o table

# ── Secret: sinh một lần, lưu local ─────────────────────────────────────────
SECRETS_FILE=.azure-demo.env
if [[ ! -f $SECRETS_FILE ]]; then
    SUFFIX=$(openssl rand -hex 3)                     # tên Web App/Postgres phải duy nhất toàn Azure
    cat > "$SECRETS_FILE" <<EOF
SUFFIX=$SUFFIX
DB_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=')Aa1
JWT_SECRET=$(openssl rand -base64 32)
ADMIN_SEED_PASSWORD=$(openssl rand -base64 12 | tr -d '/+=')Aa1!
FRONTEND_ORIGIN=
EOF
    echo "Đã tạo $SECRETS_FILE — giữ file này, không commit."
fi
# shellcheck disable=SC1090
source "$SECRETS_FILE"
if [[ -n $FRONTEND_ORIGIN ]]; then
    sed -i "s|^FRONTEND_ORIGIN=.*|FRONTEND_ORIGIN=$FRONTEND_ORIGIN|" "$SECRETS_FILE"
else
    FRONTEND_ORIGIN=$(grep '^FRONTEND_ORIGIN=' "$SECRETS_FILE" | cut -d= -f2-)
fi
CORS_ORIGINS="${FRONTEND_ORIGIN:-http://localhost:3000}"

APP="quiktech-pos-api-${SUFFIX}"
PG="quiktech-pos-db-${SUFFIX}"

# ── 1. Hạ tầng (bỏ qua bước đã có) ──────────────────────────────────────────
echo "==> Provider"
for ns in Microsoft.Web Microsoft.DBforPostgreSQL; do
    az provider register --namespace "$ns" --wait --only-show-errors
done

if ! az group show -n "$RG" -o none 2>/dev/null; then
    echo "==> Resource group $RG ($LOCATION)"
    az group create -n "$RG" -l "$LOCATION" -o none
fi

if ! az postgres flexible-server show -n "$PG" -g "$RG" -o none 2>/dev/null; then
    echo "==> PostgreSQL $PG (Burstable B1ms, ~5-10 phút)"
    # --public-access 0.0.0.0: chỉ cho dịch vụ trong Azure kết nối (không mở cho Internet)
    az postgres flexible-server create -n "$PG" -g "$RG" -l "$LOCATION" \
        --tier Burstable --sku-name Standard_B1ms --storage-size 32 --version 16 \
        --admin-user "$DB_ADMIN" --admin-password "$DB_PASSWORD" \
        --public-access 0.0.0.0 --yes -o none
fi
# Rule 0.0.0.0 = cho mọi dịch vụ trong Azure kết nối. Tạo lại mỗi lần (create = create-or-update)
# vì `server create` có thể lỗi giữa chừng sau khi server đã tạo nhưng trước khi tạo rule.
az postgres flexible-server firewall-rule create -g "$RG" --server-name "$PG" \
    --name AllowAllAzureServices --start-ip-address 0.0.0.0 --end-ip-address 0.0.0.0 -o none
# Azure chỉ cho CREATE EXTENSION với extension đã allow-list — migration V1 dùng unaccent.
# BTREE_GIST: cho HRM chạy chung server này (hrm/api/scripts/azure-deploy-shared-quiktech.sh) — tham số
# chung cả server và bị ghi đè cả danh sách, nên bỏ đi sẽ làm hỏng migration của HRM.
az postgres flexible-server parameter set -g "$RG" --server-name "$PG" \
    --name azure.extensions --value UNACCENT,BTREE_GIST -o none
if ! az postgres flexible-server db show -g "$RG" --server-name "$PG" --name "$DB_NAME" -o none 2>/dev/null; then
    az postgres flexible-server db create -g "$RG" --server-name "$PG" --name "$DB_NAME" -o none
fi

if ! az appservice plan show -n "$PLAN" -g "$RG" -o none 2>/dev/null; then
    echo "==> App Service Plan $PLAN (Linux B1)"
    az appservice plan create -n "$PLAN" -g "$RG" -l "$LOCATION" --is-linux --sku B1 -o none
fi

if ! az webapp show -n "$APP" -g "$RG" -o none 2>/dev/null; then
    echo "==> Web App $APP (Java 25)"
    az webapp create -n "$APP" -g "$RG" -p "$PLAN" --runtime "JAVA|25-java25" -o none
fi

# ── 2. Cấu hình (chạy lại mỗi lần để đồng bộ) ───────────────────────────────
echo "==> Cấu hình Web App"
# Always On: không bao giờ tắt → không cold start; health check /readyz: treo thì Azure tự restart.
# F1 không hỗ trợ Always On — giữ nguyên gói hiện tại (đổi gói bằng scripts/azure-scale.sh)
ALWAYS_ON=true
[[ $(az appservice plan show -n "$PLAN" -g "$RG" --query sku.name -o tsv) == F1 ]] && ALWAYS_ON=false
az webapp config set -n "$APP" -g "$RG" --always-on "$ALWAYS_ON" --generic-configurations healthCheckPath=/readyz \
    --ftps-state Disabled -o none
az webapp update -n "$APP" -g "$RG" --https-only true -o none

DB_URL="jdbc:postgresql://$PG.postgres.database.azure.com:5432/$DB_NAME?sslmode=require"
az webapp config appsettings set -n "$APP" -g "$RG" -o none --settings \
    "DB_URL=$DB_URL" "DB_USERNAME=$DB_ADMIN" "DB_PASSWORD=$DB_PASSWORD" \
    "JWT_SECRET=$JWT_SECRET" "ADMIN_SEED_PASSWORD=$ADMIN_SEED_PASSWORD" \
    "REDIS_ENABLED=false" "SEED_ENABLED=true" "CORS_ORIGINS=$CORS_ORIGINS" \
    "RATE_LIMIT_LOGIN_MAX_REQUESTS=30" "RATE_LIMIT_REGISTER_MAX_REQUESTS=20" "RATE_LIMIT_REFRESH_MAX_REQUESTS=100" \
    "PAYMENT_QR_STORAGE_PATH=/home/storage/payment-account-qr" \
    "JAVA_OPTS=-XX:MaxRAMPercentage=75" \
    "WEBSITES_CONTAINER_START_TIME_LIMIT=600"
# Dòng rate limit: sau front-end của App Service nhiều người dùng có thể chung một IP — nới quota auth.
# /home được giữ qua các lần restart → ảnh QR upload không mất.
# Start time limit 600s: lần đầu Flyway + seed trên B1 có thể lâu hơn mặc định 230s.

# ── 3. Build JAR bằng application.properties.example ────────────────────────
# File local (gitignored) có secret mặc định cho dev — không được lọt vào JAR deploy.
# Tạm đổi tên file local, build bằng file mẫu, rồi trả lại dù build thành công hay lỗi.
PROPS=src/main/resources/application.properties
if [[ -f $PROPS ]]; then
    mv "$PROPS" "$PROPS.local-backup"
    trap 'mv -f "$PROPS.local-backup" "$PROPS"' EXIT
else
    trap 'rm -f "$PROPS"' EXIT
fi
cp src/main/resources/application.properties.example "$PROPS"
echo "==> Build $JAR"
# mvnw cần Git Bash tự đổi đường dẫn /c/... cho java.exe → bỏ MSYS_NO_PATHCONV riêng cho lệnh này
env -u MSYS_NO_PATHCONV ./mvnw -B -q clean package -DskipTests

# ── 4. Upload JAR — Azure lưu thành /home/site/wwwroot/app.jar và restart ───
echo "==> Deploy JAR lên $APP"
az webapp deploy -n "$APP" -g "$RG" --src-path "$JAR" --type jar -o none

HOST=$(az webapp show -n "$APP" -g "$RG" --query defaultHostName -o tsv)
echo "==> Chờ /readyz (lần đầu gồm Flyway + seed, có thể ~1-3 phút)"
for _ in $(seq 1 60); do
    if [[ $(curl -s -o /dev/null -m 20 -w "%{http_code}" "https://$HOST/readyz") == 200 ]]; then
        echo
        echo "API:        https://$HOST"
        echo "Swagger:    https://$HOST/swagger-ui/index.html"
        echo "CORS:       $CORS_ORIGINS"
        echo "Admin:      admin / (ADMIN_SEED_PASSWORD trong $SECRETS_FILE)"
        exit 0
    fi
    sleep 5
done
echo "API chưa sẵn sàng — xem log: az webapp log tail -n $APP -g $RG" >&2
exit 1
