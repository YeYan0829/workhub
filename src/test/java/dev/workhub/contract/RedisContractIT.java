package dev.workhub.contract;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class RedisContractIT {
    @Test void pingSetGetAndExpiry() {
        var factory=new LettuceConnectionFactory("127.0.0.1",Integer.parseInt(ContractSupport.env("REDIS_PORT","16379")));
        factory.afterPropertiesSet();factory.start();
        String key="p0:"+UUID.randomUUID();var redis=new StringRedisTemplate(factory);
        try {
            try(var connection=factory.getConnection()) { assertThat(connection.ping()).isEqualTo("PONG"); }
            redis.opsForValue().set(key,"contract",Duration.ofSeconds(2));
            assertThat(redis.opsForValue().get(key)).isEqualTo("contract");
            assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(1L,2000L);
            await().atMost(5,TimeUnit.SECONDS).untilAsserted(() -> assertThat(redis.hasKey(key)).isFalse());
        } finally { redis.delete(key);factory.destroy(); }
    }
}
