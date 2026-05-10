package com.omniflow.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Xử lý toàn bộ vòng đời của JWT: tạo token, parse claims, và xác minh tính hợp lệ.
 *
 * <h3>Cấu trúc token</h3>
 * JWT gồm 3 phần phân tách bởi dấu chấm: {@code Header.Payload.Signature}
 * <ul>
 *   <li>{@code sub} — username; dùng để identify user</li>
 *   <li>{@code userId} — internal ID; dùng để build {@link UserPrincipal} trong filter</li>
 *   <li>{@code roles} — danh sách global role (VD: {@code ["SUPER_ADMIN"]}); chỉ chứa
 *       global roles vì store-scoped roles phụ thuộc context store, không nhúng vào token</li>
 *   <li>{@code iat} — issued-at: thời điểm cấp token (Unix epoch ms)</li>
 *   <li>{@code exp} — expiration: thời điểm hết hạn = iat + {@code jwt.expiration}</li>
 * </ul>
 *
 * <h3>Thuật toán ký</h3>
 * HMAC-SHA256 (HS256) với secret key đọc từ {@code jwt.secret} (Base64-encoded).
 * JJWT tự chọn thuật toán phù hợp dựa trên độ dài key khi gọi {@code signWith()}.
 *
 * <h3>Thành phần liên quan</h3>
 * <ul>
 *   <li>{@link JwtAuthFilter} — gọi {@link #isTokenValid(String)}, {@link #extractUsername},
 *       {@link #extractUserId}, {@link #extractRoles} trên mỗi request để build SecurityContext</li>
 *   <li>{@link com.omniflow.backend.service.AuthService} — gọi {@link #generateToken}
 *       sau khi đăng ký / đăng nhập thành công</li>
 * </ul>
 */
@Service
public class JwtService {

    /**
     * Secret key dạng Base64-encoded string, đọc từ {@code jwt.secret} trong application.properties.
     * Phải đủ dài (tối thiểu 256-bit / 32 byte sau decode) để đáp ứng yêu cầu bảo mật của HS256.
     */
    @Value("${jwt.secret}")
    private String secret;

    /**
     * Thời gian sống của token tính bằng milliseconds, đọc từ {@code jwt.expiration}.
     * Mặc định 86400000ms = 24 giờ.
     */
    @Value("${jwt.expiration}")
    private long expiration;

    /**
     * Tạo JWT token đã ký với các claim tùy chỉnh.
     *
     * <p>Thứ tự builder quan trọng: {@code claims()} phải gọi trước {@code subject()} vì
     * {@code claims()} ghi đè toàn bộ payload map — nếu gọi sau sẽ xóa mất {@code sub}.
     *
     * @param userDetails  object chứa username (dùng làm {@code sub} claim)
     * @param extraClaims  map các claim bổ sung — thường là {@code {userId, roles}}
     * @return JWT string dạng {@code header.payload.signature}
     */
    public String generateToken(UserDetails userDetails, Map<String, Object> extraClaims) {
        return Jwts.builder()
                .claims(extraClaims)          // set custom claims trước
                .subject(userDetails.getUsername()) // set sub sau để không bị overwrite
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())    // JJWT tự chọn HS256/HS384/HS512 theo key size
                .compact();
    }

    /**
     * Lấy username từ claim {@code sub} của token.
     *
     * @param token JWT string (không bao gồm prefix "Bearer ")
     * @return username; ném exception nếu token bị giả mạo hoặc malformed
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Lấy userId từ claim {@code userId}.
     *
     * <p>Cần xử lý nhiều kiểu số vì JSON parser của JJWT (Jackson) deserialize số nguyên
     * thành {@code Integer} nếu giá trị nằm trong range int, hoặc {@code Long} nếu lớn hơn.
     * Không thể dùng {@code Claims::get("userId", Long.class)} vì nó không tự ép kiểu.
     *
     * @param token JWT string
     * @return userId; {@code null} nếu claim không tồn tại
     */
    public Long extractUserId(String token) {
        Object val = extractAllClaims(token).get("userId");
        if (val instanceof Long l)    return l;
        if (val instanceof Integer i) return i.longValue();
        if (val instanceof Number n)  return n.longValue();
        return null;
    }

    /**
     * Lấy danh sách global roles từ claim {@code roles}.
     *
     * <p>Claim này chứa tên role dạng string (VD: {@code "SUPER_ADMIN"}), không phải enum.
     * {@link JwtAuthFilter} sẽ prefix {@code "ROLE_"} trước khi tạo {@code GrantedAuthority}.
     *
     * @param token JWT string
     * @return danh sách role name; trả về empty list nếu claim không tồn tại
     */
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Object val = extractAllClaims(token).get("roles");
        if (val instanceof List<?> list) return (List<String>) list;
        return List.of();
    }

    /**
     * Kiểm tra token hợp lệ: chữ ký đúng và chưa hết hạn.
     *
     * <p>Không cần {@code UserDetails} vì thông tin user (username, userId) đã có trong claims.
     * {@code extractAllClaims()} tự động xác minh chữ ký — ném {@code SignatureException}
     * nếu token bị giả mạo.
     *
     * @param token JWT string
     * @return {@code true} nếu chữ ký hợp lệ và token chưa hết hạn
     */
    public boolean isTokenValid(String token) {
        try {
            return !isTokenExpired(token);
        } catch (ExpiredJwtException e) {
            // JJWT ném exception khi parse token hết hạn thay vì trả về expiration để so sánh
            return false;
        }
    }

    /**
     * Kiểm tra token hợp lệ kèm so khớp username với {@code UserDetails}.
     *
     * @deprecated Dùng {@link #isTokenValid(String)} — phiên bản mới không cần load UserDetails từ DB.
     *             Giữ lại để tránh break nếu có code ngoài đang gọi overload này.
     */
    @Deprecated
    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            return extractUsername(token).equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (ExpiredJwtException e) {
            return false;
        }
    }

    /**
     * Trích xuất một claim bất kỳ từ token bằng hàm mapper.
     *
     * @param token          JWT string
     * @param claimsResolver hàm lấy giá trị từ {@link Claims}
     *                       (VD: {@code Claims::getSubject}, {@code c -> c.get("userId")})
     * @return giá trị của claim
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(extractAllClaims(token));
    }

    /**
     * So sánh expiration claim với thời điểm hiện tại.
     * Được gọi sau khi token đã pass chữ ký — claim {@code exp} đã đáng tin cậy.
     */
    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    /**
     * Parse và xác minh JWT, trả về phần payload (Claims).
     *
     * <p>JJWT tự động xác minh chữ ký HMAC trong bước này.
     * Ném các exception sau nếu token không hợp lệ:
     * <ul>
     *   <li>{@code SignatureException} — chữ ký sai (token bị giả mạo)</li>
     *   <li>{@code MalformedJwtException} — token không đúng format JWT</li>
     *   <li>{@code ExpiredJwtException} — token hết hạn</li>
     * </ul>
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey()) // set key để verify chữ ký
                .build()
                .parseSignedClaims(token)   // parse + verify; ném exception nếu invalid
                .getPayload();              // trả về phần payload (claims)
    }

    /**
     * Decode Base64 secret từ config và tạo {@link SecretKey} cho HMAC-SHA.
     *
     * <p>Tạo mới mỗi lần gọi — chi phí thấp, không cần cache field.
     * {@code secret} là Base64-encoded string thay vì plain text để tránh lộ key
     * trong log hoặc stack trace.
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
