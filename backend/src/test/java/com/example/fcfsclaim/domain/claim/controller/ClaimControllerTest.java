package com.example.fcfsclaim.domain.claim.controller;

import com.example.fcfsclaim.domain.claim.dto.ClaimRequest;
import com.example.fcfsclaim.domain.claim.dto.ClaimResponse;
import com.example.fcfsclaim.domain.claim.service.ClaimService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ClaimController.class)
class ClaimControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ClaimService claimService;

    private ClaimRequest request() {
        return new ClaimRequest(1L, 10L, "test-token", 100L);
    }

    @Test
    void claim_200() throws Exception {
        when(claimService.claim(any())).thenReturn(ClaimResponse.ok());

        mockMvc.perform(post("/api/v1/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void claim_401_토큰없음() throws Exception {
        when(claimService.claim(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 토큰입니다."));

        mockMvc.perform(post("/api/v1/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void claim_409_재고소진() throws Exception {
        when(claimService.claim(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "해당 상품의 재고가 소진되었습니다."));

        mockMvc.perform(post("/api/v1/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isConflict());
    }
}
