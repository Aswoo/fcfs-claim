package com.example.fcfsclaim.domain.queue.controller;

import com.example.fcfsclaim.common.response.ApiResponse;
import com.example.fcfsclaim.domain.queue.dto.EnterRequest;
import com.example.fcfsclaim.domain.queue.dto.EnterResponse;
import com.example.fcfsclaim.domain.queue.dto.StatusResponse;
import com.example.fcfsclaim.domain.queue.service.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @PostMapping("/enter")
    public ApiResponse<EnterResponse> enter(@RequestBody EnterRequest request) {
        return ApiResponse.ok(queueService.enter(request.userId(), request.eventId()));
    }

    @GetMapping("/status")
    public ApiResponse<StatusResponse> status(@RequestParam Long userId,
                                               @RequestParam Long eventId) {
        return ApiResponse.ok(queueService.getStatus(userId, eventId));
    }
}
