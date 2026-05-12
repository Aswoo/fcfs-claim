package com.example.fcfsclaim.domain.queue.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueReadySubscriberTest {

    @Mock SseEmitterStore emitterStore;
    @Mock ObjectMapper objectMapper;

    @InjectMocks QueueReadySubscriber subscriber;

    private static final Long EVENT_ID = 10L;
    private static final Long USER_ID  = 1L;
    private static final String TOKEN  = "test-token";

    private Message message(String body) {
        Message msg = mock(Message.class);
        when(msg.getBody()).thenReturn(body.getBytes());
        return msg;
    }

    private QueueReadyMessage readyMsg() {
        return new QueueReadyMessage(EVENT_ID, USER_ID, TOKEN);
    }

    // ── 정상 흐름 ────────────────────────────────────────────────────────

    @Test
    void 정상_emitter에_token_전송_후_complete() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        when(objectMapper.readValue(any(byte[].class), eq(QueueReadyMessage.class))).thenReturn(readyMsg());
        when(emitterStore.get(EVENT_ID, USER_ID)).thenReturn(emitter);

        subscriber.onMessage(message("{}"), null);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    // ── emitter 없음 ─────────────────────────────────────────────────────

    @Test
    void emitter가_null이면_전송_안함() throws Exception {
        when(objectMapper.readValue(any(byte[].class), eq(QueueReadyMessage.class))).thenReturn(readyMsg());
        when(emitterStore.get(EVENT_ID, USER_ID)).thenReturn(null);

        subscriber.onMessage(message("{}"), null);

        // store 조회 이후 아무 동작 없음
        verify(emitterStore, never()).remove(any(), any());
    }

    // ── IOException ──────────────────────────────────────────────────────

    @Test
    void send_IOException_발생시_emitter_제거() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        when(objectMapper.readValue(any(byte[].class), eq(QueueReadyMessage.class))).thenReturn(readyMsg());
        when(emitterStore.get(EVENT_ID, USER_ID)).thenReturn(emitter);
        doThrow(new IOException("connection closed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        subscriber.onMessage(message("{}"), null);

        verify(emitterStore).remove(EVENT_ID, USER_ID);
        verify(emitter, never()).complete(); // IOException 발생 시 complete 미호출
    }

    // ── JSON 파싱 실패 ───────────────────────────────────────────────────

    @Test
    void 잘못된_JSON_예외처리_emitter_미호출() throws Exception {
        when(objectMapper.readValue(any(byte[].class), eq(QueueReadyMessage.class)))
                .thenThrow(new JsonProcessingException("bad json") {});

        subscriber.onMessage(message("invalid-json"), null);

        verify(emitterStore, never()).get(any(), any());
        verify(emitterStore, never()).remove(any(), any());
    }
}
