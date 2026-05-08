package com.example.fcfsclaim.domain.queue.service;

import com.example.fcfsclaim.common.config.RedisConfig;
import com.example.fcfsclaim.domain.event.service.ActiveEventCache;
import com.example.fcfsclaim.domain.queue.dto.EnterResponse;
import com.example.fcfsclaim.domain.queue.dto.StatusResponse;
import com.example.fcfsclaim.domain.queue.entity.QueueToken;
import com.example.fcfsclaim.domain.queue.repository.QueueTokenRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final int PROCESS_PER_SECOND = 10;
    private static final Duration TOKEN_TTL = Duration.ofSeconds(300);

    private final StringRedisTemplate redis;
    private final QueueTokenRepository queueTokenRepository;
    private final ObjectMapper objectMapper;
    private final ActiveEventCache activeEventCache;  // DB 대신 캐시에서 읽음

    private String waitingKey(Long eventId)   { return "queue:waiting:" + eventId; }
    private String userTokenKey(Long eventId, Long userId) {
        return "user:token:" + eventId + ":" + userId;
    }
    private String tokenKey(Long eventId, String token) {
        return "token:" + eventId + ":" + token;
    }

    public EnterResponse enter(Long userId, Long eventId) {
        if (!activeEventCache.getAll().contains(eventId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "진행 중인 이벤트가 없습니다.");
        }

        String key = waitingKey(eventId);
        redis.opsForZSet().addIfAbsent(key, userId.toString(), System.currentTimeMillis());

        if (redis.hasKey(userTokenKey(eventId, userId))) {
            return new EnterResponse(0);
        }

        Long rank = redis.opsForZSet().rank(key, userId.toString());
        return new EnterResponse(rank == null ? 1 : rank + 1);
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

    @Scheduled(fixedDelay = 1000)
    @SchedulerLock(name = "processQueue", lockAtMostFor = "PT2S", lockAtLeastFor = "PT1S")
    public void processQueue() {
        Set<Long> eventIds = activeEventCache.getAll();  // 메모리 읽기 — DB 조회 없음

        if (eventIds.isEmpty()) return;

        for (Long eventId : eventIds) {
            processQueueForEvent(eventId);
        }
    }

    private void processQueueForEvent(Long eventId) {
        Set<ZSetOperations.TypedTuple<String>> users =
                redis.opsForZSet().popMin(waitingKey(eventId), PROCESS_PER_SECOND);

        if (users == null || users.isEmpty()) return;

        for (ZSetOperations.TypedTuple<String> entry : users) {
            Long userId = Long.valueOf(entry.getValue());
            String token = UUID.randomUUID().toString();

            redis.opsForValue().set(userTokenKey(eventId, userId), token, TOKEN_TTL);
            redis.opsForValue().set(tokenKey(eventId, token), userId.toString(), TOKEN_TTL);
            queueTokenRepository.save(QueueToken.of(eventId, userId, token));
            publish(new QueueReadyMessage(eventId, userId, token));
        }
    }

    private void publish(QueueReadyMessage msg) {
        try {
            redis.convertAndSend(RedisConfig.QUEUE_READY_CHANNEL, objectMapper.writeValueAsString(msg));
        } catch (JsonProcessingException e) {
            log.warn("Pub/Sub 발행 실패 userId={}", msg.userId());
        }
    }
}
