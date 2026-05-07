package com.example.fcfsclaim.domain.queue.service;

import com.example.fcfsclaim.domain.queue.entity.TokenStatus;
import com.example.fcfsclaim.domain.queue.repository.QueueTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenExpiryService {

    private final QueueTokenRepository queueTokenRepository;

    @Transactional
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "expireTokens", lockAtMostFor = "PT55S", lockAtLeastFor = "PT30S")
    public void expireOverdueTokens() {
        int count = queueTokenRepository.expireOverdue(
                TokenStatus.VALID,
                TokenStatus.EXPIRED,
                LocalDateTime.now()
        );
        if (count > 0) {
            log.info("만료 처리된 토큰: {}개", count);
        }
    }
}
