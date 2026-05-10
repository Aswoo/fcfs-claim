package com.example.fcfsclaim.domain.event.service;

import com.example.fcfsclaim.domain.event.entity.EventStatus;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveEventCache {

    private final EventRepository eventRepository;

    // AtomicReference: add()/remove()의 read-modify-write와 refresh()의 덮어쓰기 사이
    // 경쟁 조건을 CAS로 방지. volatile은 가시성만 보장하고 원자성은 보장하지 않음.
    private final AtomicReference<Set<Long>> activeEventIds = new AtomicReference<>(Set.of());

    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        Set<Long> updated = eventRepository.findByStatus(EventStatus.ACTIVE)
                .stream()
                .map(e -> e.getId())
                .collect(Collectors.toUnmodifiableSet());

        activeEventIds.set(updated);
        log.debug("ActiveEventCache 갱신: {}개", updated.size());
    }

    public Set<Long> getAll() {
        return activeEventIds.get();
    }

    public void add(Long eventId) {
        activeEventIds.updateAndGet(current -> {
            Set<Long> updated = new HashSet<>(current);
            updated.add(eventId);
            return Set.copyOf(updated);
        });
    }

    public void remove(Long eventId) {
        activeEventIds.updateAndGet(current -> {
            Set<Long> updated = new HashSet<>(current);
            updated.remove(eventId);
            return Set.copyOf(updated);
        });
    }
}
