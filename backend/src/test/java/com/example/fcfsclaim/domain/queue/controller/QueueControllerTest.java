package com.example.fcfsclaim.domain.queue.controller;

import com.example.fcfsclaim.domain.queue.dto.EnterRequest;
import com.example.fcfsclaim.domain.queue.dto.EnterResponse;
import com.example.fcfsclaim.domain.queue.dto.StatusResponse;
import com.example.fcfsclaim.domain.queue.service.QueueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(QueueController.class)
class QueueControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean QueueService queueService;

    // ── 대기열 입장 ──────────────────────────────────────────────────────────

    @Test
    void enter_200_rank_반환() throws Exception {
        when(queueService.enter(1L, 10L)).thenReturn(new EnterResponse(5));

        mockMvc.perform(post("/api/v1/queue/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EnterRequest(1L, 10L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rank").value(5));
    }

    @Test
    void enter_body없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/queue/enter")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── 상태 조회 ────────────────────────────────────────────────────────────

    @Test
    void status_대기중_isReady_false() throws Exception {
        when(queueService.getStatus(1L, 10L)).thenReturn(StatusResponse.waiting(3));

        mockMvc.perform(get("/api/v1/queue/status")
                        .param("userId", "1")
                        .param("eventId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rank").value(3));
    }

    @Test
    void status_토큰발급완료_token_반환() throws Exception {
        when(queueService.getStatus(1L, 10L)).thenReturn(StatusResponse.ready("abc-token"));

        mockMvc.perform(get("/api/v1/queue/status")
                        .param("userId", "1")
                        .param("eventId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("abc-token"));
    }

    @Test
    void status_파라미터_누락시_400() throws Exception {
        mockMvc.perform(get("/api/v1/queue/status")
                        .param("userId", "1"))  // eventId 누락
                .andExpect(status().isBadRequest());
    }
}
