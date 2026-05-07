package com.example.fcfsclaim.domain.queue.service;

import com.example.fcfsclaim.domain.event.service.EventLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueEndedSubscriber implements MessageListener {

    private final EventLifecycleService lifecycleService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Long eventId = Long.valueOf(new String(message.getBody()));
            // 이 인스턴스에 연결된 해당 이벤트의 모든 SSE에 종료 알림
            lifecycleService.notifyEventEnded(eventId);
        } catch (Exception e) {
            log.warn("queue:ended 처리 실패: {}", e.getMessage());
        }
    }
}
