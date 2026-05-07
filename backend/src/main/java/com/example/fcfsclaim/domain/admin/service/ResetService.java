package com.example.fcfsclaim.domain.admin.service;

import com.example.fcfsclaim.domain.claim.repository.ClaimRepository;
import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import com.example.fcfsclaim.domain.event.service.ActiveEventCache;
import com.example.fcfsclaim.domain.product.repository.ProductRepository;
import com.example.fcfsclaim.domain.queue.repository.QueueTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class ResetService {

    private static final String QUEUE_ENDED_CHANNEL = "queue:ended";

    private final ClaimRepository claimRepository;
    private final QueueTokenRepository queueTokenRepository;
    private final ProductRepository productRepository;
    private final EventRepository eventRepository;
    private final ActiveEventCache activeEventCache;
    private final StringRedisTemplate redis;

    @Transactional
    public void reset() {
        claimRepository.deleteAllInBatch();
        queueTokenRepository.deleteAllInBatch();
        productRepository.resetAllStock();
        deleteRedisPattern("queue:waiting:*");
        deleteRedisPattern("token:*");
        deleteRedisPattern("user:token:*");
    }

    // ── 테스트 전용 admin 메서드 ────────────────────────────────────────────

    @Transactional
    public void forceActivate(Long eventId) {
        Event event = findEvent(eventId);
        event.activate();
        activeEventCache.add(eventId);
    }

    @Transactional
    public void forceEnd(Long eventId) {
        Event event = findEvent(eventId);
        if (event.isEnded()) return;
        event.end();
        activeEventCache.remove(eventId);
        redis.delete("queue:waiting:" + eventId);
        redis.convertAndSend(QUEUE_ENDED_CHANNEL, String.valueOf(eventId));
    }

    @Transactional
    public void forceSchedule(Long eventId) {
        Event event = findEvent(eventId);
        event.rescheduleToScheduled();
        activeEventCache.remove(eventId);
    }

    // Redis 토큰 키만 삭제 → claim 시 401 발생 (만료 시뮬레이션)
    public void expireToken(Long eventId, String token) {
        redis.delete("token:" + eventId + ":" + token);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Event findEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."));
    }

    private void deleteRedisPattern(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
