package com.example.fcfsclaim.domain.queue.controller;

import com.example.fcfsclaim.domain.queue.service.SseEmitterStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
public class SseController {

    private final SseEmitterStore emitterStore;

    @GetMapping("/subscribe")
    public SseEmitter subscribe(@RequestParam Long userId, @RequestParam Long eventId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5분 타임아웃

        emitterStore.put(eventId, userId, emitter);
        emitter.onCompletion(() -> emitterStore.remove(eventId, userId));
        emitter.onTimeout(() -> emitterStore.remove(eventId, userId));
        emitter.onError(e -> emitterStore.remove(eventId, userId));

        return emitter;
    }
}
