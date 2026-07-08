package com.quiktech.backend.service;

import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.entity.RefreshToken;
import com.quiktech.backend.exception.InvalidTokenException;
import com.quiktech.backend.repository.RefreshTokenRepository;
import com.quiktech.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    @Value("${jwt.refresh-token-expiration-days:30}")
    private int expirationDays;

    @Transactional
    public String create(Long userId) {
        RefreshToken token = RefreshToken.builder()
                .token(generateToken())
                .user(userRepository.getReferenceById(userId))
                .expiresAt(Instant.now().plus(expirationDays, ChronoUnit.DAYS))
                .build();
        return refreshTokenRepository.save(token).getToken();
    }

    /**
     * Validates and rotates a refresh token (issues a new one, revokes the old).
     * If the token is already revoked, all user tokens are revoked (reuse / theft detection).
     * <p>
     * Lưu ý transaction: method này và caller ({@code AuthService.refresh}) đều khai báo
     * {@code noRollbackFor = InvalidTokenException} — bắt buộc, vì các nhánh lỗi bên dưới
     * vừa GHI DB (revoke-all khi phát hiện reuse, đánh dấu revoke khi hết hạn) vừa ném
     * exception. Nếu để rollback mặc định của RuntimeException thì các UPDATE đó bị hủy
     * và cơ chế theft-detection vô hiệu hoàn toàn (bug CRITICAL trong review 02_auth_security).
     */
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public RotateResult rotate(String tokenValue) {
        RefreshToken existing = refreshTokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new InvalidTokenException(
                        ErrorCode.REFRESH_TOKEN_INVALID, "Invalid refresh token"));

        // Revoke atomic (UPDATE ... WHERE revoked_at IS NULL): chống race khi 2 request
        // refresh song song cùng 1 token — chỉ 1 request nhận affected = 1 và đi tiếp,
        // request kia nhận 0 và bị xử lý như reuse ở nhánh dưới.
        int revoked = refreshTokenRepository.revokeIfActive(tokenValue, Instant.now());

        if (revoked == 0) {
            // Reuse detected — potential token theft; invalidate the entire token family
            refreshTokenRepository.revokeAllByUserId(existing.getUser().getId(), Instant.now());
            throw new InvalidTokenException(
                    ErrorCode.REFRESH_TOKEN_INVALID, "Refresh token already used");
        }

        if (existing.getExpiresAt().isBefore(Instant.now())) {
            // Token đã bị đánh dấu revoke bởi UPDATE atomic ở trên — chỉ cần báo lỗi
            throw new InvalidTokenException(
                    ErrorCode.REFRESH_TOKEN_EXPIRED, "Refresh token expired");
        }

        Long userId = existing.getUser().getId();

        String newTokenValue = generateToken();
        RefreshToken newToken = RefreshToken.builder()
                .token(newTokenValue)
                .user(userRepository.getReferenceById(userId))
                .expiresAt(Instant.now().plus(expirationDays, ChronoUnit.DAYS))
                .build();
        refreshTokenRepository.save(newToken);

        return new RotateResult(newTokenValue, userId);
    }

    @Transactional
    public void revokeAll(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    public record RotateResult(String newToken, Long userId) {}
}
