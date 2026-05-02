package com.example.fcfsclaim.domain.queue.service;

import com.example.fcfsclaim.domain.queue.dto.EnterResponse;
import com.example.fcfsclaim.domain.queue.dto.StatusResponse;
import com.example.fcfsclaim.domain.queue.entity.QueueToken;
import com.example.fcfsclaim.domain.queue.repository.QueueTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final int PROCESS_PER_SECOND = 10;
    private static final Duration TOKEN_TTL = Duration.ofSeconds(300);

    private final StringRedisTemplate redis;
    private final SseEmitterStore emitterStore;
    private final QueueTokenRepository queueTokenRepository;

    // Redis 키
    private String waitingKey(Long eventId)   { return "queue:waiting:" + eventId; }
    private String userTokenKey(Long eventId, Long userId) {
        return "user:token:" + eventId + ":" + userId;
    }
    private String tokenKey(Long eventId, String token) {
        return "token:" + eventId + ":" + token;
    }

    public EnterResponse enter(Long userId, Long eventId) {
        String key = waitingKey(eventId);

        // 이미 입장한 유저면 ZADD가 무시됨 (score 업데이트 안 함)
        redis.opsForZSet().addIfAbsent(key, userId.toString(), System.currentTimeMillis());

        // 토큰이 이미 발급된 유저면 rank 0 반환
        if (redis.hasKey(userTokenKey(eventId, userId))) {
            return new EnterResponse(0);
        }

        Long rank = redis.opsForZSet().rank(key, userId.toString());
        return new EnterResponse(rank == null ? 1 : rank + 1);  // 0-based → 1-based
    }

    public StatusResponse getStatus(Long userId, Long eventId) {
        String token = redis.opsForValue().get(userTokenKey(eventId, userId));
        if (token != null) {
            return StatusResponse.ready(token);
        }

        Long rank = redis.opsForZSet().rank(waitingKey(eventId), userId.toString());
        return StatusResponse.waiting(rank == null ? 0 : rank + 1);
    }

    public boolean validateToken(String token, Long eventId) {
        return redis.hasKey(tokenKey(eventId, token));
    }

    private void pushReady(Long eventId, Long userId, String token) {
        SseEmitter emitter = emitterStore.get(eventId, userId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("ready")
                    .data(Map.of("token", token)));
            emitter.complete();
        } catch (IOException e) {
            log.warn("SSE 전송 실패 userId={}", userId);
            emitterStore.remove(eventId, userId);
        }
    }

    @Scheduled(fixedDelay = 1000)
    @SchedulerLock(name = "processQueue", lockAtMostFor = "PT2S", lockAtLeastFor = "PT1S")
    public void processQueue() {
        // 현재 운영 중인 이벤트 ID — 추후 DB에서 조회하도록 확장 가능
        Long eventId = 1L;

        Set<ZSetOperations.TypedTuple<String>> users =
                redis.opsForZSet().popMin(waitingKey(eventId), PROCESS_PER_SECOND);

        if (users == null || users.isEmpty()) return;

        for (ZSetOperations.TypedTuple<String> entry : users) {
            Long userId = Long.valueOf(entry.getValue());
            String token = UUID.randomUUID().toString();

            // userId → 토큰 (getStatus 조회용)
            redis.opsForValue().set(userTokenKey(eventId, userId), token, TOKEN_TTL);
            // 토큰 → userId (Claim 검증용)
            redis.opsForValue().set(tokenKey(eventId, token), userId.toString(), TOKEN_TTL);
            // DB 영구 기록
            queueTokenRepository.save(QueueToken.of(eventId, userId, token));

            // SSE로 즉시 푸시
            pushReady(eventId, userId, token);
        }
    }
}
