package com.example.fcfsclaim;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@SpringBootTest
class FcfsClaimApplicationTests {

    // 테스트 환경에 Redis가 없으므로 Pub/Sub 리스너 컨테이너만 mock 처리
    @MockBean
    RedisMessageListenerContainer redisMessageListenerContainer;

    @Test
    void contextLoads() {
    }

}
