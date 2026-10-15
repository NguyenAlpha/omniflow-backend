package com.quiktech.pos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** REDIS_ENABLED=false: app phải khởi động được khi không có Redis (port 1 không có gì lắng nghe). */
@SpringBootTest(properties = {"redis.enabled=false", "spring.data.redis.port=1"})
class RedisDisabledContextTests {

	@Test
	void contextLoadsWithoutRedis() {
	}

}
