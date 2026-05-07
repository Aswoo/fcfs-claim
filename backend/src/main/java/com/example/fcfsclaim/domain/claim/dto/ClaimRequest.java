package com.example.fcfsclaim.domain.claim.dto;

public record ClaimRequest(Long userId, Long eventId, String token, Long productId) {}
