package com.example.fcfsclaim.domain.queue.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseEmitterStore {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    private String key(Long eventId, Long userId) {
        return eventId + ":" + userId;
    }

    public void put(Long eventId, Long userId, SseEmitter emitter) {
        emitters.put(key(eventId, userId), emitter);
    }

    public SseEmitter get(Long eventId, Long userId) {
        return emitters.get(key(eventId, userId));
    }

    public void remove(Long eventId, Long userId) {
        emitters.remove(key(eventId, userId));
    }
}
