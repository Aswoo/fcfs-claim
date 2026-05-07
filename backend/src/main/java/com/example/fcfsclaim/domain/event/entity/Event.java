package com.example.fcfsclaim.domain.event.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EventStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static Event of(String name, LocalDateTime startAt, LocalDateTime endAt) {
        Event e = new Event();
        e.name = name;
        e.startAt = startAt;
        e.endAt = endAt;
        e.status = EventStatus.SCHEDULED;
        e.createdAt = LocalDateTime.now();
        return e;
    }

    public void activate() {
        this.status = EventStatus.ACTIVE;
    }

    public void end() {
        this.status = EventStatus.ENDED;
    }

    public boolean isEnded() {
        return this.status == EventStatus.ENDED;
    }

    // 테스트용: 상태를 SCHEDULED로 되돌림
    public void rescheduleToScheduled() {
        this.status = EventStatus.SCHEDULED;
    }
}
