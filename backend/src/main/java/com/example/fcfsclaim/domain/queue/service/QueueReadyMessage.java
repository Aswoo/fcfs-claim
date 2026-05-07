package com.example.fcfsclaim.domain.queue.service;

public record QueueReadyMessage(Long eventId, Long userId, String token) {}
