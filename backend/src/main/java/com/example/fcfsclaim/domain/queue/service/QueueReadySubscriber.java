package com.example.fcfsclaim.domain.queue.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueReadySubscriber implements MessageListener {

    private final SseEmitterStore emitterStore;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            QueueReadyMessage msg = objectMapper.readValue(message.getBody(), QueueReadyMessage.class);
            pushReady(msg.eventId(), msg.userId(), msg.token());
        } catch (Exception e) {
            log.warn("Pub/Sub 메시지 처리 실패: {}", e.getMessage());
        }
    }

    private void pushReady(Long eventId, Long userId, String token) {
        SseEmitter emitter = emitterStore.get(eventId, userId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("ready")
                    .data(Map.of("token", token)));
            emitter.complete();
        } catch (IOException e) {
            log.warn("SSE 전송 실패 userId={}", userId);
            emitterStore.remove(eventId, userId);
        }
    }
}
