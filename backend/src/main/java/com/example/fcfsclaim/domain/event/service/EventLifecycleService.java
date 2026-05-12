package com.example.fcfsclaim.domain.event.service;

import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.entity.EventStatus;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import com.example.fcfsclaim.domain.queue.service.SseEmitterStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.context.ApplicationEventPublisher;
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

    private final EventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SseEmitterStore emitterStore;
    private final LockProvider lockProvider;

    public void activateEvent(Long eventId) {
        Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(
                Instant.now(),
                "activateEvent-" + eventId,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5)
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
        if (event == null || event.getStatus() != EventStatus.SCHEDULED) return;

        event.activate();
        // AFTER_COMMIT 이후 EventLifecycleListener.onActivated() 에서 캐시 반영
        eventPublisher.publishEvent(new EventActivatedEvent(eventId));
        log.info("이벤트 {} 활성화 완료", eventId);
    }

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
        // AFTER_COMMIT 이후 EventLifecycleListener.onEnded() 에서 캐시 제거 + Redis 정리 + Pub/Sub 발행
        eventPublisher.publishEvent(new EventEndedEvent(eventId));
        log.info("이벤트 {} 종료 완료", eventId);
    }

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
