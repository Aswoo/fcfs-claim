package com.example.fcfsclaim.domain.admin.controller;

import com.example.fcfsclaim.common.response.ApiResponse;
import com.example.fcfsclaim.domain.admin.service.ResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class ResetController {

    private final ResetService resetService;

    @PostMapping("/reset")
    public ApiResponse<Void> reset() {
        resetService.reset();
        return ApiResponse.ok(null);
    }

    // ── 테스트 전용: 이벤트 상태 강제 전환 ──────────────────────────────────

    @PostMapping("/force-activate/{eventId}")
    public ApiResponse<Void> forceActivate(@PathVariable Long eventId) {
        resetService.forceActivate(eventId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/force-end/{eventId}")
    public ApiResponse<Void> forceEnd(@PathVariable Long eventId) {
        resetService.forceEnd(eventId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/force-schedule/{eventId}")
    public ApiResponse<Void> forceSchedule(@PathVariable Long eventId) {
        resetService.forceSchedule(eventId);
        return ApiResponse.ok(null);
    }

    // ── 테스트 전용: 토큰 만료 시뮬레이션 ──────────────────────────────────

    @DeleteMapping("/token")
    public ApiResponse<Void> expireToken(
            @RequestParam Long eventId,
            @RequestParam String token) {
        resetService.expireToken(eventId, token);
        return ApiResponse.ok(null);
    }
}
