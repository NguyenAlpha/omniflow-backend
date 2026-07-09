package com.quiktech.backend.service;

import com.quiktech.backend.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Dọn dẹp định kỳ bảng refresh_tokens — không có job này thì bảng phình vô hạn:
 * mỗi lần refresh tạo 1 row mới (token rotation), row cũ chỉ bị revoke chứ
 * không bao giờ bị xóa.
 *
 * <p>Chỉ xóa token đã <b>hết hạn</b> quá {@code retention-days} ngày. Token revoked
 * nhưng chưa hết hạn được giữ nguyên — chúng là "bẫy" cho reuse-detection: kẻ trộm
 * dùng lại token đã rotate sẽ bị phát hiện và toàn bộ phiên của user bị thu hồi
 * (xem {@code RefreshTokenService.rotate}). Giữ thêm vài ngày sau khi hết hạn
 * để phục vụ điều tra sự cố nếu cần.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupScheduler {

    private final RefreshTokenRepository refreshTokenRepository;

    /** Số ngày giữ lại token sau khi hết hạn trước khi xóa cứng. */
    @Value("${refresh-token.cleanup.retention-days:7}")
    private int retentionDays;

    /** Chạy hàng ngày lúc 02:00 AM — sau job subscription expiry (01:00 AM). */
    @Scheduled(cron = "${refresh-token.cleanup.cron:0 0 2 * * *}")
    @Transactional
    public void deleteExpiredTokens() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = refreshTokenRepository.deleteAllExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} expired refresh token(s) older than {} day(s)", deleted, retentionDays);
        }
    }
}
