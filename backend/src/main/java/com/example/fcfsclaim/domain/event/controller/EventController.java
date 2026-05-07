package com.example.fcfsclaim.domain.event.controller;

import com.example.fcfsclaim.common.response.ApiResponse;
import com.example.fcfsclaim.domain.event.dto.EventStatusResponse;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventRepository eventRepository;

    @GetMapping("/{id}/status")
    public ApiResponse<EventStatusResponse> getStatus(@PathVariable Long id) {
        return eventRepository.findById(id)
                .map(event -> ApiResponse.ok(EventStatusResponse.from(event)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."));
    }
}
