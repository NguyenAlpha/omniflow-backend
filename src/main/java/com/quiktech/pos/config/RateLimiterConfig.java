package com.quiktech.pos.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.Assert;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

/**
 * Cấu hình hạ tầng Redis cho rate limit (Bucket4j).
 *
 * <p>Mỗi quota là một "bucket" token lưu trong Redis, nên mọi instance API dùng chung
 * một bộ đếm — chạy nhiều instance sau load balancer vẫn giới hạn đúng. Class này chỉ
 * dựng kết nối và {@link LettuceBasedProxyManager}; quota cụ thể nằm ở
 * {@code RateLimitFilter} / {@code AuthenticatedRateLimitFilter}, còn logic kiểm tra
 * và fail-open nằm ở {@code RateLimitService}.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Kết nối Redis riêng cho rate limit, dùng codec {@code byte[]} (Bucket4j lưu trạng thái
     * bucket dạng nhị phân, key cũng là {@code byte[]}).
     *
     * <p>Tái sử dụng {@link RedisClient} gốc của {@link LettuceConnectionFactory} mà Spring
     * Boot đã cấu hình (host, port, mật khẩu, SSL), nhưng mở một connection riêng để lệnh
     * rate limit không dùng chung codec String của {@code StringRedisTemplate}. Connection
     * được đóng khi context tắt ({@code destroyMethod = "close"}).
     */
    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<byte[], byte[]> rateLimitRedisConnection(RedisConnectionFactory connectionFactory) {
        RedisClient redisClient = (RedisClient) ((LettuceConnectionFactory) connectionFactory).getNativeClient();
        return redisClient.connect(ByteArrayCodec.INSTANCE);
    }

    /**
     * ProxyManager của Bucket4j: đọc/ghi bucket trong Redis bằng compare-and-swap (CAS),
     * nên nhiều request song song trên nhiều instance trừ token an toàn mà không cần lock.
     *
     * <ul>
     *   <li><b>Request timeout</b> ({@code rate-limit.redis.timeout-millis}, mặc định 200 ms):
     *       Redis chậm không được làm treo request của người dùng. Quá thời gian thì lệnh
     *       lỗi, và {@code RateLimitService} cho request đi qua (fail-open).</li>
     *   <li><b>Expiration</b>: key bucket tự hết hạn sau thời gian cần để bucket nạp đầy lại
     *       + 10 giây. Khi đó bucket đã về trạng thái đầy nên xóa đi không đổi kết quả,
     *       và Redis không tích tụ key của IP/user đã ngừng gửi request.</li>
     * </ul>
     *
     * @param timeoutMillis phải dương — kiểm tra ngay lúc khởi động để cấu hình sai báo lỗi sớm
     */
    @Bean
    public LettuceBasedProxyManager<byte[]> rateLimitProxyManager(
            StatefulRedisConnection<byte[], byte[]> rateLimitRedisConnection,
            @Value("${rate-limit.redis.timeout-millis:200}") long timeoutMillis) {
        Assert.isTrue(timeoutMillis > 0, "rate-limit.redis.timeout-millis must be positive");
        return LettuceBasedProxyManager.builderFor(rateLimitRedisConnection)
                .withClientSideConfig(ClientSideConfig.getDefault().withRequestTimeout(Duration.ofMillis(timeoutMillis)))
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                .build();
    }
}
