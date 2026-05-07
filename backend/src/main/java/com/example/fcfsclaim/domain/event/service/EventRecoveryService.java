package com.example.fcfsclaim.domain.event.service;

import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.entity.EventStatus;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventRecoveryService implements ApplicationRunner {

    // ApplicationRunner: 스프링 컨텍스트가 완전히 준비된 후 실행
    // @PostConstruct보다 늦게 실행 → JPA, 트랜잭션, 스케줄러 모두 사용 가능

    private final EventRepository eventRepository;
    private final EventLifecycleService lifecycleService;
    private final ActiveEventCache activeEventCache;
    private final TaskScheduler taskScheduler;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        LocalDateTime now = LocalDateTime.now();
        log.info("이벤트 복구 시작 (기준 시각: {})", now);

        // 1. 서버 꺼진 사이 end_at이 지나버린 이벤트 → 즉시 종료 처리
        List<Event> overdue = eventRepository.findOverdue(now);
        overdue.forEach(event -> {
            log.warn("복구: 이벤트 {} 이미 종료됨 (end_at={}), 즉시 처리", event.getId(), event.getEndAt());
            lifecycleService.endEvent(event.getId());
        });

        // 2. SCHEDULED인데 start_at이 지난 이벤트 → 즉시 활성화
        List<Event> activatable = eventRepository.findActivatable(now);
        activatable.forEach(event -> {
            log.info("복구: 이벤트 {} 활성화 (start_at={})", event.getId(), event.getStartAt());
            lifecycleService.activateEvent(event.getId());
            scheduleEnd(event);  // 종료 예약도 다시 등록
        });

        // 3. 이미 ACTIVE인 이벤트 → 종료 예약만 재등록 (TaskScheduler가 재시작으로 초기화됨)
        List<Event> active = eventRepository.findByStatus(EventStatus.ACTIVE);
        active.forEach(event -> {
            activeEventCache.add(event.getId());  // 캐시 즉시 반영
            if (event.getEndAt().isAfter(now)) {
                scheduleEnd(event);
                log.info("복구: 이벤트 {} 종료 재예약 (end_at={})", event.getId(), event.getEndAt());
            }
        });

        // 4. SCHEDULED이고 start_at이 아직 안 됐으면 → 활성화 + 종료 모두 예약
        List<Event> scheduled = eventRepository.findByStatus(EventStatus.SCHEDULED);
        scheduled.stream()
                .filter(e -> e.getStartAt().isAfter(now))
                .forEach(event -> {
                    scheduleActivation(event);
                    scheduleEnd(event);
                    log.info("복구: 이벤트 {} 활성화/종료 예약 (start={}, end={})",
                            event.getId(), event.getStartAt(), event.getEndAt());
                });

        log.info("이벤트 복구 완료");
    }

    // TaskScheduler: "이 시각에 이 람다를 실행해줘"
    // 서버 재시작 시 메모리가 초기화되므로 항상 재등록 필요
    public void scheduleActivation(Event event) {
        taskScheduler.schedule(
                () -> lifecycleService.activateEvent(event.getId()),
                event.getStartAt().atZone(ZoneId.systemDefault()).toInstant()
        );
    }

    public void scheduleEnd(Event event) {
        taskScheduler.schedule(
                () -> lifecycleService.endEvent(event.getId()),
                event.getEndAt().atZone(ZoneId.systemDefault()).toInstant()
        );
    }
}
