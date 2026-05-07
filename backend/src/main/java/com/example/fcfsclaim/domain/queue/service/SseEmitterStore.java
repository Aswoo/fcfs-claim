package com.example.fcfsclaim.domain.queue.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class SseEmitterStore {

    // key: "eventId:userId"
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

    // 이벤트 종료 알림: 특정 이벤트에 연결된 모든 emitter 반환
    // key 형식이 "eventId:userId"이므로 prefix로 필터링
    public Map<Long, SseEmitter> getByEventId(Long eventId) {
        String prefix = eventId + ":";
        return emitters.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .collect(Collectors.toMap(
                        entry -> Long.valueOf(entry.getKey().substring(prefix.length())),
                        Map.Entry::getValue
                ));
    }
}
