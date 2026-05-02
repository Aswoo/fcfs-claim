package com.example.fcfsclaim.domain.queue.dto;

public record StatusResponse(boolean isReady, long rank, String token) {

    public static StatusResponse waiting(long rank) {
        return new StatusResponse(false, rank, null);
    }

    public static StatusResponse ready(String token) {
        return new StatusResponse(true, 0, token);
    }
}
