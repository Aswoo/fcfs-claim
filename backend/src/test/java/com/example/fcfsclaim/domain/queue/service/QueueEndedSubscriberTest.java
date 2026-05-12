package com.example.fcfsclaim.domain.queue.service;

import com.example.fcfsclaim.domain.event.service.EventLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueEndedSubscriberTest {

    @Mock EventLifecycleService lifecycleService;

    @InjectMocks QueueEndedSubscriber subscriber;

    private Message message(String body) {
        Message msg = mock(Message.class);
        when(msg.getBody()).thenReturn(body.getBytes());
        return msg;
    }

    @Test
    void 정상_eventId_파싱_후_notifyEventEnded_호출() {
        subscriber.onMessage(message("42"), null);

        verify(lifecycleService).notifyEventEnded(42L);
    }

    @Test
    void 잘못된_메시지_예외_발생해도_notifyEventEnded_미호출() {
        subscriber.onMessage(message("not-a-number"), null);

        verify(lifecycleService, never()).notifyEventEnded(any());
    }
}
