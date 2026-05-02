package com.example.fcfsclaim.domain.claim.dto;

public record ClaimResponse(boolean success, String message) {

    public static ClaimResponse ok() {
        return new ClaimResponse(true, "클레임 성공");
    }
}
