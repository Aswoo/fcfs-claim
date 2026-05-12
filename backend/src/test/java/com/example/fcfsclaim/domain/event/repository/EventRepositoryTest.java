package com.example.fcfsclaim.domain.event.repository;

import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.entity.EventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class EventRepositoryTest {

    @Autowired EventRepository eventRepository;

    private Event save(EventStatus status, LocalDateTime startAt, LocalDateTime endAt) {
        Event e = Event.of("테스트이벤트", startAt, endAt);
        if (status == EventStatus.ACTIVE) e.activate();
        if (status == EventStatus.ENDED)  { e.activate(); e.end(); }
        return eventRepository.save(e);
    }

    // ── findActivatable ──────────────────────────────────────────────────

    @Test
    void findActivatable_start_at_지났고_end_at_안지난_SCHEDULED_반환() {
        LocalDateTime now = LocalDateTime.now();
        save(EventStatus.SCHEDULED, now.minusMinutes(1), now.plusHours(1));  // 대상
        save(EventStatus.SCHEDULED, now.plusHours(1),   now.plusHours(2));   // start_at 미래 — 제외
        save(EventStatus.ACTIVE,    now.minusMinutes(1), now.plusHours(1));  // ACTIVE — 제외

        List<Event> result = eventRepository.findActivatable(now);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(EventStatus.SCHEDULED);
    }

    @Test
    void findActivatable_end_at_지난_SCHEDULED_제외() {
        LocalDateTime now = LocalDateTime.now();
        save(EventStatus.SCHEDULED, now.minusHours(2), now.minusHours(1));  // end_at 경과 — 제외

        List<Event> result = eventRepository.findActivatable(now);

        assertThat(result).isEmpty();
    }

    // ── findOverdue ──────────────────────────────────────────────────────

    @Test
    void findOverdue_end_at_지난_SCHEDULED_ACTIVE_반환() {
        LocalDateTime now = LocalDateTime.now();
        save(EventStatus.SCHEDULED, now.minusHours(2), now.minusMinutes(1));  // 대상
        save(EventStatus.ACTIVE,    now.minusHours(2), now.minusMinutes(1));  // 대상
        save(EventStatus.ENDED,     now.minusHours(2), now.minusMinutes(1));  // ENDED — 제외
        save(EventStatus.ACTIVE,    now.minusHours(1), now.plusHours(1));     // end_at 미래 — 제외

        List<Event> result = eventRepository.findOverdue(now);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Event::getStatus)
                .containsExactlyInAnyOrder(EventStatus.SCHEDULED, EventStatus.ACTIVE);
    }

    @Test
    void findOverdue_end_at_미래면_제외() {
        LocalDateTime now = LocalDateTime.now();
        save(EventStatus.ACTIVE, now.minusHours(1), now.plusMinutes(1));

        List<Event> result = eventRepository.findOverdue(now);

        assertThat(result).isEmpty();
    }
}
