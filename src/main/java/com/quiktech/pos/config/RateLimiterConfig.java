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

@Configuration
public class RateLimiterConfig {

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<byte[], byte[]> rateLimitRedisConnection(RedisConnectionFactory connectionFactory) {
        RedisClient redisClient = (RedisClient) ((LettuceConnectionFactory) connectionFactory).getNativeClient();
        return redisClient.connect(ByteArrayCodec.INSTANCE);
    }

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
