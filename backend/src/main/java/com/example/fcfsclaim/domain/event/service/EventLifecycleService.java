package com.example.fcfsclaim.domain.event.service;

import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import com.example.fcfsclaim.domain.queue.service.SseEmitterStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventLifecycleService {

    private static final String QUEUE_ENDED_CHANNEL = "queue:ended";

    private final EventRepository eventRepository;
    private final ActiveEventCache activeEventCache;
    private final StringRedisTemplate redis;
    private final SseEmitterStore emitterStore;
    private final LockProvider lockProvider;  // ShedLock 프로그래매틱 API

    // TaskScheduler에서 호출됨 (파드마다 각자 예약)
    // → 여러 파드가 동시에 이 메서드를 호출하므로 ShedLock으로 1개만 실행
    public void activateEvent(Long eventId) {
        // 락 이름을 동적으로 생성: "activateEvent-1", "activateEvent-2" ...
        // @SchedulerLock 어노테이션은 상수 이름만 지원 → 프로그래매틱 API 필요
        Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(
                Instant.now(),
                "activateEvent-" + eventId,
                Duration.ofSeconds(30),   // lockAtMostFor: 비정상 종료 시 30초 후 자동 해제
                Duration.ofSeconds(5)     // lockAtLeastFor: 최소 5초 락 유지
        ));

        if (lock.isEmpty()) {
            log.debug("이벤트 {} 활성화: 다른 인스턴스가 처리 중", eventId);
            return;
        }

        try {
            doActivate(eventId);
        } finally {
            lock.get().unlock();
        }
    }

    @Transactional
    protected void doActivate(Long eventId) {
        Event event = eventRepository.findById(eventId).orElse(null);
        if (event == null || !event.getStatus().name().equals("SCHEDULED")) return;

        event.activate();
        activeEventCache.add(eventId);  // 30초 기다리지 않고 즉시 캐시 반영
        log.info("이벤트 {} 활성화 완료", eventId);
    }

    // end_at 정각에 TaskScheduler가 호출
    public void endEvent(Long eventId) {
        Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(
                Instant.now(),
                "endEvent-" + eventId,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5)
        ));

        if (lock.isEmpty()) {
            log.debug("이벤트 {} 종료: 다른 인스턴스가 처리 중", eventId);
            return;
        }

        try {
            doEnd(eventId);
        } finally {
            lock.get().unlock();
        }
    }

    @Transactional
    protected void doEnd(Long eventId) {
        Event event = eventRepository.findById(eventId).orElse(null);
        if (event == null || event.isEnded()) return;

        event.end();
        activeEventCache.remove(eventId);               // 캐시 즉시 제거
        redis.delete("queue:waiting:" + eventId);       // Redis 대기열 정리
        redis.convertAndSend(QUEUE_ENDED_CHANNEL, String.valueOf(eventId)); // SSE 종료 알림
        log.info("이벤트 {} 종료 완료", eventId);
    }

    // 이벤트가 종료됐을 때 이 인스턴스에 연결된 SSE 전체에 알림
    public void notifyEventEnded(Long eventId) {
        Map<Long, SseEmitter> emitters = emitterStore.getByEventId(eventId);
        emitters.forEach((userId, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("ended")
                        .data("이벤트가 종료됐습니다."));
                emitter.complete();
            } catch (IOException e) {
                emitterStore.remove(eventId, userId);
            }
        });
        log.info("이벤트 {} 종료 알림: {}명", eventId, emitters.size());
    }
}
