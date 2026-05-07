package com.example.fcfsclaim.domain.queue.service;

import com.example.fcfsclaim.domain.event.service.ActiveEventCache;
import com.example.fcfsclaim.domain.queue.dto.EnterResponse;
import com.example.fcfsclaim.domain.queue.dto.StatusResponse;
import com.example.fcfsclaim.domain.queue.repository.QueueTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueueServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ZSetOperations<String, String> zSetOps;
    @Mock QueueTokenRepository queueTokenRepository;
    @Mock ObjectMapper objectMapper;
    @Mock ActiveEventCache activeEventCache;

    @InjectMocks QueueService queueService;

    private static final Long USER_ID  = 1L;
    private static final Long EVENT_ID = 10L;

    private String waitingKey()            { return "queue:waiting:" + EVENT_ID; }
    private String userTokenKey()          { return "user:token:" + EVENT_ID + ":" + USER_ID; }
    private String tokenKey(String token)  { return "token:" + EVENT_ID + ":" + token; }

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zSetOps);
    }

    @Test
    void enter_신규입장_rank반환() {
        when(zSetOps.addIfAbsent(eq(waitingKey()), eq(USER_ID.toString()), anyDouble())).thenReturn(true);
        when(redis.hasKey(userTokenKey())).thenReturn(false);
        when(zSetOps.rank(waitingKey(), USER_ID.toString())).thenReturn(0L);  // 0-indexed → rank 1

        EnterResponse response = queueService.enter(USER_ID, EVENT_ID);

        assertThat(response.rank()).isEqualTo(1L);
        verify(zSetOps).addIfAbsent(eq(waitingKey()), eq(USER_ID.toString()), anyDouble());
    }

    @Test
    void enter_중복입장_기존rank반환() {
        when(zSetOps.addIfAbsent(eq(waitingKey()), eq(USER_ID.toString()), anyDouble())).thenReturn(false);
        when(redis.hasKey(userTokenKey())).thenReturn(true);  // 이미 토큰 있음

        EnterResponse response = queueService.enter(USER_ID, EVENT_ID);

        assertThat(response.rank()).isEqualTo(0L);
    }

    @Test
    void getStatus_대기중() {
        when(valueOps.get(userTokenKey())).thenReturn(null);   // 토큰 없음
        when(zSetOps.rank(waitingKey(), USER_ID.toString())).thenReturn(4L);  // 0-indexed 4 → rank 5

        StatusResponse response = queueService.getStatus(USER_ID, EVENT_ID);

        assertThat(response.isReady()).isFalse();
        assertThat(response.rank()).isEqualTo(5L);
    }

    @Test
    void getStatus_토큰발급완료() {
        String token = "issued-token";
        when(valueOps.get(userTokenKey())).thenReturn(token);

        StatusResponse response = queueService.getStatus(USER_ID, EVENT_ID);

        assertThat(response.isReady()).isTrue();
        assertThat(response.token()).isEqualTo(token);
    }

    @Test
    void validateToken_유효() {
        String token = "valid-token";
        when(redis.hasKey(tokenKey(token))).thenReturn(true);

        assertThat(queueService.validateToken(token, EVENT_ID)).isTrue();
    }

    @Test
    void validateToken_만료() {
        String token = "expired-token";
        when(redis.hasKey(tokenKey(token))).thenReturn(false);

        assertThat(queueService.validateToken(token, EVENT_ID)).isFalse();
    }
}
