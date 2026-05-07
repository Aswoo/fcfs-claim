package com.example.fcfsclaim.domain.event.dto;

import com.example.fcfsclaim.domain.event.entity.Event;

import java.time.LocalDateTime;

public record EventStatusResponse(
        Long id,
        String status,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
    public static EventStatusResponse from(Event event) {
        return new EventStatusResponse(
                event.getId(),
                event.getStatus().name(),
                event.getStartAt(),
                event.getEndAt()
        );
    }
}
