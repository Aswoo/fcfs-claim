package com.example.fcfsclaim.domain.event.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EventLifecycleListenerTest {

    @Mock ActiveEventCache activeEventCache;
    @Mock StringRedisTemplate redis;

    @InjectMocks EventLifecycleListener listener;

    private static final Long EVENT_ID = 10L;

    @Test
    void onActivated_캐시_등록() {
        listener.onActivated(new EventActivatedEvent(EVENT_ID));

        verify(activeEventCache).add(EVENT_ID);
    }

    @Test
    void onEnded_캐시제거_대기열삭제_PubSub발행() {
        listener.onEnded(new EventEndedEvent(EVENT_ID));

        verify(activeEventCache).remove(EVENT_ID);
        verify(redis).delete("queue:waiting:" + EVENT_ID);
        verify(redis).convertAndSend("queue:ended", String.valueOf(EVENT_ID));
    }
}
