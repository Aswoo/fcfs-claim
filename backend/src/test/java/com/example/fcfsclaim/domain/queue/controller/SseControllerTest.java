package com.example.fcfsclaim.domain.queue.controller;

import com.example.fcfsclaim.domain.queue.service.SseEmitterStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

@WebMvcTest(SseController.class)
class SseControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean SseEmitterStore emitterStore;

    @Test
    void subscribe_emitter_등록() throws Exception {
        mockMvc.perform(get("/api/v1/queue/subscribe")
                        .param("userId", "1")
                        .param("eventId", "10"))
                .andExpect(request().asyncStarted());

        // emitterStore.put()은 SseEmitter 반환 전에 동기적으로 호출됨
        verify(emitterStore).put(eq(10L), eq(1L), any(SseEmitter.class));
    }
}
