package com.example.fcfsclaim.domain.claim.service;

import com.example.fcfsclaim.domain.claim.dto.ClaimRequest;
import com.example.fcfsclaim.domain.claim.dto.ClaimResponse;
import com.example.fcfsclaim.domain.claim.entity.Claim;
import com.example.fcfsclaim.domain.claim.repository.ClaimRepository;
import com.example.fcfsclaim.domain.queue.entity.QueueToken;
import com.example.fcfsclaim.domain.queue.repository.QueueTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ClaimService {

    private final StringRedisTemplate redis;
    private final ClaimRepository claimRepository;
    private final QueueTokenRepository queueTokenRepository;

    @Transactional
    public ClaimResponse claim(ClaimRequest request) {
        String tokenRedisKey = "token:" + request.eventId() + ":" + request.token();

        // 1. Redis에서 토큰 유효성 검증 (빠른 경로)
        String storedUserId = redis.opsForValue().get(tokenRedisKey);
        if (storedUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 토큰입니다.");
        }
        if (!storedUserId.equals(request.userId().toString())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "토큰 소유자가 다릅니다.");
        }

        // 2. 중복 클레임 방지 (DB unique constraint)
        try {
            claimRepository.save(Claim.of(request.eventId(), request.userId(), request.token()));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 수령하셨습니다.");
        }

        // 3. 토큰 소진 처리
        redis.delete(tokenRedisKey);
        queueTokenRepository.findByToken(request.token())
                .ifPresent(QueueToken::markUsed);

        return ClaimResponse.ok();
    }
}
