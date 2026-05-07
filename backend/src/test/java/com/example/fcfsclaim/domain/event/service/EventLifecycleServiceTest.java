package com.example.fcfsclaim.domain.event.service;

import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.entity.EventStatus;
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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventLifecycleServiceTest {

    @Mock EventRepository eventRepository;
    @Mock ActiveEventCache activeEventCache;
    @Mock StringRedisTemplate redis;
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
        Event event = scheduledEvent();
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        lifecycleService.activateEvent(EVENT_ID);

        verify(activeEventCache).add(EVENT_ID);
        verify(simpleLock).unlock();
    }

    @Test
    void activateEvent_락경합_스킵() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.empty());

        lifecycleService.activateEvent(EVENT_ID);

        verify(eventRepository, never()).findById(any());
        verify(activeEventCache, never()).add(any());
    }

    @Test
    void endEvent_성공() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        Event event = activeEvent();
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        lifecycleService.endEvent(EVENT_ID);

        verify(activeEventCache).remove(EVENT_ID);
        verify(redis).delete("queue:waiting:" + EVENT_ID);
        verify(redis).convertAndSend(eq("queue:ended"), eq(String.valueOf(EVENT_ID)));
        verify(simpleLock).unlock();
    }

    @Test
    void endEvent_이미종료된이벤트_스킵() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        Event event = activeEvent();
        event.end();   // 이미 ENDED 상태
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        lifecycleService.endEvent(EVENT_ID);

        verify(activeEventCache, never()).remove(any());
        verify(redis, never()).delete(anyString());
    }
}
