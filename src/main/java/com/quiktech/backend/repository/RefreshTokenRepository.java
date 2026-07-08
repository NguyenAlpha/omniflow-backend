package com.quiktech.backend.repository;

import com.quiktech.backend.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now WHERE rt.user.id = :userId AND rt.revokedAt IS NULL")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Revoke atomic: chỉ thành công (return 1) nếu token CHƯA bị revoke.
     * Điều kiện {@code revokedAt IS NULL} nằm ngay trong UPDATE nên 2 request
     * refresh song song cùng 1 token sẽ được DB serialize — đúng 1 request thắng,
     * request còn lại nhận 0 row và rơi vào nhánh reuse-detection.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now WHERE rt.token = :token AND rt.revokedAt IS NULL")
    int revokeIfActive(@Param("token") String token, @Param("now") Instant now);
}
