package com.example.fcfsclaim.domain.event.service;

import com.example.fcfsclaim.domain.event.entity.EventStatus;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveEventCache {

    private final EventRepository eventRepository;

    // volatile: 여러 스레드(스케줄러 스레드, HTTP 스레드)가 동시에 읽으므로
    //           가시성 보장을 위해 volatile 선언
    //           Set 자체를 교체(참조 교체)하므로 volatile이면 충분 (내부 수정 없음)
    private volatile Set<Long> activeEventIds = Set.of();

    // ShedLock 없음: 캐시는 각 인스턴스가 독립적으로 관리해야 함
    // 한 인스턴스만 refresh하면 나머지는 캐시가 갱신되지 않음
    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        Set<Long> updated = eventRepository.findByStatus(EventStatus.ACTIVE)
                .stream()
                .map(e -> e.getId())
                .collect(Collectors.toUnmodifiableSet());

        this.activeEventIds = updated;
        log.debug("ActiveEventCache 갱신: {}개", updated.size());
    }

    public Set<Long> getAll() {
        return activeEventIds;
    }

    // 이벤트가 ACTIVE가 되는 순간 캐시에 즉시 추가 (30초 기다릴 필요 없음)
    public void add(Long eventId) {
        Set<Long> updated = new java.util.HashSet<>(activeEventIds);
        updated.add(eventId);
        this.activeEventIds = Set.copyOf(updated);
    }

    // 이벤트가 ENDED가 되는 순간 캐시에서 즉시 제거
    public void remove(Long eventId) {
        Set<Long> updated = new java.util.HashSet<>(activeEventIds);
        updated.remove(eventId);
        this.activeEventIds = Set.copyOf(updated);
    }
}
