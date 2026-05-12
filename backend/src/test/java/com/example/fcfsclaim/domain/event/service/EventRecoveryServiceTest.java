package com.example.fcfsclaim.domain.event.service;

import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.entity.EventStatus;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.ApplicationArguments;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventRecoveryServiceTest {

    @Mock EventRepository eventRepository;
    @Mock EventLifecycleService lifecycleService;
    @Mock ActiveEventCache activeEventCache;
    @Mock TaskScheduler taskScheduler;
    @Mock ApplicationArguments args;
    @Mock ScheduledFuture<?> scheduledFuture;

    @InjectMocks EventRecoveryService recoveryService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn((ScheduledFuture) scheduledFuture);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private Event event(long id, LocalDateTime startAt, LocalDateTime endAt) {
        Event e = Event.of("이벤트-" + id, startAt, endAt);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private Event activeEvent(long id, LocalDateTime endAt) {
        Event e = event(id, LocalDateTime.now().minusHours(1), endAt);
        e.activate();
        return e;
    }

    // ── 시나리오 ① ───────────────────────────────────────────────────────

    @Test
    void 시나리오1_end_at_경과_즉시_종료처리() {
        Event overdue = activeEvent(1L, LocalDateTime.now().minusMinutes(30));

        when(eventRepository.findOverdue(any())).thenReturn(List.of(overdue));
        when(eventRepository.findActivatable(any())).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.ACTIVE)).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.SCHEDULED)).thenReturn(List.of());

        recoveryService.run(args);

        verify(lifecycleService).endEvent(1L);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void 시나리오1_복수_overdue_모두_종료처리() {
        Event e1 = activeEvent(1L, LocalDateTime.now().minusHours(2));
        Event e2 = activeEvent(2L, LocalDateTime.now().minusMinutes(10));

        when(eventRepository.findOverdue(any())).thenReturn(List.of(e1, e2));
        when(eventRepository.findActivatable(any())).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.ACTIVE)).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.SCHEDULED)).thenReturn(List.of());

        recoveryService.run(args);

        verify(lifecycleService).endEvent(1L);
        verify(lifecycleService).endEvent(2L);
    }

    // ── 시나리오 ② ───────────────────────────────────────────────────────

    @Test
    void 시나리오2_start_at_경과_SCHEDULED_즉시_활성화_후_종료예약() {
        // SCHEDULED 상태, start_at < now, end_at > now
        Event activatable = event(2L,
                LocalDateTime.now().minusMinutes(30),
                LocalDateTime.now().plusHours(1));

        when(eventRepository.findOverdue(any())).thenReturn(List.of());
        when(eventRepository.findActivatable(any())).thenReturn(List.of(activatable));
        when(eventRepository.findByStatus(EventStatus.ACTIVE)).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.SCHEDULED)).thenReturn(List.of());

        recoveryService.run(args);

        verify(lifecycleService).activateEvent(2L);
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class)); // scheduleEnd
    }

    // ── 시나리오 ③ ───────────────────────────────────────────────────────

    @Test
    void 시나리오3_ACTIVE_캐시복원_종료예약() {
        Event active = activeEvent(3L, LocalDateTime.now().plusHours(1));

        when(eventRepository.findOverdue(any())).thenReturn(List.of());
        when(eventRepository.findActivatable(any())).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.ACTIVE)).thenReturn(List.of(active));
        when(eventRepository.findByStatus(EventStatus.SCHEDULED)).thenReturn(List.of());

        recoveryService.run(args);

        verify(activeEventCache).add(3L);
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void 시나리오3_ACTIVE이지만_end_at_경과_종료예약_안함() {
        // findOverdue에서 잡혔어야 하지만, ACTIVE 목록에도 있을 경우 scheduleEnd 미호출 검증
        Event active = activeEvent(3L, LocalDateTime.now().minusMinutes(5));

        when(eventRepository.findOverdue(any())).thenReturn(List.of());
        when(eventRepository.findActivatable(any())).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.ACTIVE)).thenReturn(List.of(active));
        when(eventRepository.findByStatus(EventStatus.SCHEDULED)).thenReturn(List.of());

        recoveryService.run(args);

        verify(activeEventCache).add(3L);                                                  // 캐시는 복원
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));  // 종료 예약 안 함
    }

    // ── 시나리오 ④ ───────────────────────────────────────────────────────

    @Test
    void 시나리오4_SCHEDULED_start_at_미래_활성화_종료_모두예약() {
        Event scheduled = event(4L,
                LocalDateTime.now().plusMinutes(30),
                LocalDateTime.now().plusHours(2));

        when(eventRepository.findOverdue(any())).thenReturn(List.of());
        when(eventRepository.findActivatable(any())).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.ACTIVE)).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.SCHEDULED)).thenReturn(List.of(scheduled));

        recoveryService.run(args);

        // scheduleActivation + scheduleEnd = 2회
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void 시나리오4_SCHEDULED이지만_start_at_경과_필터링() {
        // start_at < now인 SCHEDULED는 시나리오②에서 처리됐어야 하므로 시나리오④ 필터에서 제외됨
        Event stale = event(4L,
                LocalDateTime.now().minusMinutes(10),  // start_at 이미 지남
                LocalDateTime.now().plusHours(1));

        when(eventRepository.findOverdue(any())).thenReturn(List.of());
        when(eventRepository.findActivatable(any())).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.ACTIVE)).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.SCHEDULED)).thenReturn(List.of(stale));

        recoveryService.run(args);

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    // ── 복구 없음 ────────────────────────────────────────────────────────

    @Test
    void 복구할_이벤트_없으면_아무것도_안함() {
        when(eventRepository.findOverdue(any())).thenReturn(List.of());
        when(eventRepository.findActivatable(any())).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.ACTIVE)).thenReturn(List.of());
        when(eventRepository.findByStatus(EventStatus.SCHEDULED)).thenReturn(List.of());

        recoveryService.run(args);

        verify(lifecycleService, never()).endEvent(any());
        verify(lifecycleService, never()).activateEvent(any());
        verify(activeEventCache, never()).add(any());
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }
}
