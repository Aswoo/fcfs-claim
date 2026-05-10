package com.example.fcfsclaim.domain.claim.service;

import com.example.fcfsclaim.domain.claim.dto.ClaimRequest;
import com.example.fcfsclaim.domain.claim.dto.ClaimResponse;
import com.example.fcfsclaim.domain.claim.entity.Claim;
import com.example.fcfsclaim.domain.claim.repository.ClaimRepository;
import com.example.fcfsclaim.domain.product.repository.ProductRepository;
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
    private final ProductRepository productRepository;

    private String userTokenKey(Long eventId, Long userId) {
        return "user:token:" + eventId + ":" + userId;
    }

    @Transactional
    public ClaimResponse claim(ClaimRequest request) {
        String tokenRedisKey = "token:" + request.eventId() + ":" + request.token();

        // 1. Redis 토큰 유효성 검증
        String storedUserId = redis.opsForValue().get(tokenRedisKey);
        if (storedUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 토큰입니다.");
        }
        if (!storedUserId.equals(request.userId().toString())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "토큰 소유자가 다릅니다.");
        }

        // 2. 중복 수령 방지
        try {
            claimRepository.save(Claim.of(request.eventId(), request.userId(), request.productId(), request.token()));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 수령하셨습니다.");
        }

        // 3. 선택한 상품 재고 차감 (stock > 0 조건부, 실패 시 트랜잭션 롤백)
        int updated = productRepository.decrementStock(request.productId());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "해당 상품의 재고가 소진되었습니다.");
        }

        // 4. 토큰 소진
        redis.delete(tokenRedisKey);
        redis.delete(userTokenKey(request.eventId(), request.userId()));
        queueTokenRepository.findByToken(request.token())
                .ifPresent(QueueToken::markUsed);

        return ClaimResponse.ok();
    }
}
