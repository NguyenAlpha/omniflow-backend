#!/usr/bin/env bash
# Đổi gói App Service Plan giữa F1 (miễn phí) và B1 (~12,4 USD/tháng). URL, cấu hình và DB giữ nguyên.
#
#   ./scripts/azure-scale.sh F1     # tắt Always On rồi hạ gói — F1 không hỗ trợ Always On
#   ./scripts/azure-scale.sh B1     # nâng gói rồi bật lại Always On
#
# F1: 60 phút CPU/ngày (hết thì app dừng tới hôm sau), tự tắt sau ~20 phút không có request
# → người vào đầu tiên chờ Spring Boot khởi động lại (1-3 phút).
set -euo pipefail
export MSYS_NO_PATHCONV=1
command -v az >/dev/null || PATH="/c/Program Files/Microsoft SDKs/Azure/CLI2/wbin:$PATH"

cd "$(dirname "$0")/.."
SKU="${1:-}"
[[ $SKU == F1 || $SKU == B1 ]] || { echo "Dùng: $0 F1|B1" >&2; exit 2; }

RG="${RG:-rg-quicktech-pos}"
PLAN=quiktech-pos-plan
# shellcheck disable=SC1090
source .azure-demo.env
APP="quiktech-pos-api-${SUFFIX}"

if [[ $SKU == F1 ]]; then
    echo "==> Tắt Always On"
    az webapp config set -n "$APP" -g "$RG" --always-on false -o none
    echo "==> Hạ gói $PLAN xuống F1"
    az appservice plan update -n "$PLAN" -g "$RG" --sku F1 -o none
else
    echo "==> Nâng gói $PLAN lên B1"
    az appservice plan update -n "$PLAN" -g "$RG" --sku B1 -o none
    echo "==> Bật Always On"
    az webapp config set -n "$APP" -g "$RG" --always-on true -o none
fi

HOST=$(az webapp show -n "$APP" -g "$RG" --query defaultHostName -o tsv)
echo "==> Chờ /readyz (sau khi đổi gói app khởi động lại, F1 có thể mất 1-3 phút)"
for _ in $(seq 1 60); do
    if [[ $(curl -s -o /dev/null -m 30 -w "%{http_code}" "https://$HOST/readyz") == 200 ]]; then
        echo "Đang chạy gói $(az appservice plan show -n "$PLAN" -g "$RG" --query sku.name -o tsv): https://$HOST"
        exit 0
    fi
    sleep 5
done
echo "API chưa sẵn sàng — xem log: az webapp log tail -n $APP -g $RG" >&2
exit 1
