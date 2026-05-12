package com.example.fcfsclaim.domain.event.service;

import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import com.example.fcfsclaim.domain.queue.service.SseEmitterStore;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventLifecycleServiceTest {

    @Mock EventRepository eventRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock SseEmitterStore emitterStore;
    @Mock LockProvider lockProvider;
    @Mock SimpleLock simpleLock;

    @InjectMocks EventLifecycleService lifecycleService;

    private static final Long EVENT_ID = 10L;

    private Event scheduledEvent() {
        return Event.of("테스트 이벤트",
                LocalDateTime.now().plusMinutes(1),
                LocalDateTime.now().plusMinutes(30));
    }

    private Event activeEvent() {
        Event e = scheduledEvent();
        e.activate();
        return e;
    }

    @Test
    void activateEvent_성공() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(scheduledEvent()));

        lifecycleService.activateEvent(EVENT_ID);

        // 캐시 반영은 AFTER_COMMIT 이후 EventLifecycleListener가 담당
        verify(eventPublisher).publishEvent(new EventActivatedEvent(EVENT_ID));
        verify(simpleLock).unlock();
    }

    @Test
    void activateEvent_락경합_스킵() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.empty());

        lifecycleService.activateEvent(EVENT_ID);

        verify(eventRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void endEvent_성공() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(activeEvent()));

        lifecycleService.endEvent(EVENT_ID);

        // 캐시 제거·Redis 정리·Pub/Sub은 AFTER_COMMIT 이후 EventLifecycleListener가 담당
        verify(eventPublisher).publishEvent(new EventEndedEvent(EVENT_ID));
        verify(simpleLock).unlock();
    }

    @Test
    void endEvent_이미종료된이벤트_스킵() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        Event event = activeEvent();
        event.end();
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        lifecycleService.endEvent(EVENT_ID);

        verify(eventPublisher, never()).publishEvent(any());
    }
}
