package com.example.fcfsclaim.domain.claim.controller;

import com.example.fcfsclaim.common.response.ApiResponse;
import com.example.fcfsclaim.domain.claim.dto.ClaimRequest;
import com.example.fcfsclaim.domain.claim.dto.ClaimResponse;
import com.example.fcfsclaim.domain.claim.service.ClaimService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService claimService;

    @PostMapping("/claim")
    public ApiResponse<ClaimResponse> claim(@RequestBody ClaimRequest request) {
        return ApiResponse.ok(claimService.claim(request));
    }
}
