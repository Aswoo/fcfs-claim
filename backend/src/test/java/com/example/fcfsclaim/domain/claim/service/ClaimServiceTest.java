package com.example.fcfsclaim.domain.claim.service;

import com.example.fcfsclaim.domain.claim.dto.ClaimRequest;
import com.example.fcfsclaim.domain.claim.dto.ClaimResponse;
import com.example.fcfsclaim.domain.claim.entity.Claim;
import com.example.fcfsclaim.domain.claim.repository.ClaimRepository;
import com.example.fcfsclaim.domain.product.repository.ProductRepository;
import com.example.fcfsclaim.domain.queue.entity.QueueToken;
import com.example.fcfsclaim.domain.queue.repository.QueueTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ClaimRepository claimRepository;
    @Mock QueueTokenRepository queueTokenRepository;
    @Mock ProductRepository productRepository;

    @InjectMocks ClaimService claimService;

    private static final Long USER_ID   = 1L;
    private static final Long EVENT_ID  = 10L;
    private static final Long PRODUCT_ID = 100L;
    private static final String TOKEN   = "test-token-uuid";
    private static final String TOKEN_KEY      = "token:" + EVENT_ID + ":" + TOKEN;
    private static final String USER_TOKEN_KEY = "user:token:" + EVENT_ID + ":" + USER_ID;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void claim_성공() {
        when(valueOps.get(TOKEN_KEY)).thenReturn(USER_ID.toString());
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.decrementStock(PRODUCT_ID)).thenReturn(1);

        QueueToken token = QueueToken.of(EVENT_ID, USER_ID, TOKEN);
        when(queueTokenRepository.findByToken(TOKEN)).thenReturn(Optional.of(token));

        ClaimResponse response = claimService.claim(new ClaimRequest(USER_ID, EVENT_ID, TOKEN, PRODUCT_ID));

        assertThat(response.success()).isTrue();
        verify(claimRepository).save(any(Claim.class));
        verify(productRepository).decrementStock(PRODUCT_ID);
        verify(redis).delete(TOKEN_KEY);
        // BUG-02: claim 완료 후 user:token 키도 삭제해야 한다
        // 삭제 안 하면 claim 이후에도 getStatus()가 "ready"를 반환함
        // 이 verify가 실패하면 버그 재현 성공
        verify(redis).delete(USER_TOKEN_KEY);
    }

    @Test
    void claim_토큰없음_401() {
        when(valueOps.get(TOKEN_KEY)).thenReturn(null);

        assertThatThrownBy(() -> claimService.claim(new ClaimRequest(USER_ID, EVENT_ID, TOKEN, PRODUCT_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void claim_토큰소유자불일치_403() {
        when(valueOps.get(TOKEN_KEY)).thenReturn("9999");   // 다른 userId

        assertThatThrownBy(() -> claimService.claim(new ClaimRequest(USER_ID, EVENT_ID, TOKEN, PRODUCT_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void claim_중복수령_409() {
        when(valueOps.get(TOKEN_KEY)).thenReturn(USER_ID.toString());
        when(claimRepository.save(any(Claim.class))).thenThrow(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> claimService.claim(new ClaimRequest(USER_ID, EVENT_ID, TOKEN, PRODUCT_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).contains("이미 수령");
                });
    }

    @Test
    void claim_재고소진_409() {
        when(valueOps.get(TOKEN_KEY)).thenReturn(USER_ID.toString());
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.decrementStock(PRODUCT_ID)).thenReturn(0);   // 재고 없음

        assertThatThrownBy(() -> claimService.claim(new ClaimRequest(USER_ID, EVENT_ID, TOKEN, PRODUCT_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).contains("재고");
                });
    }

    @Test
    void claim_재고소진시_Redis토큰_미삭제() {
        // 재고 소진 → 예외 → @Transactional 롤백
        // Redis.delete()는 DB 트랜잭션 범위 밖이므로 호출되지 않아야 한다
        when(valueOps.get(TOKEN_KEY)).thenReturn(USER_ID.toString());
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.decrementStock(PRODUCT_ID)).thenReturn(0);

        assertThatThrownBy(() -> claimService.claim(new ClaimRequest(USER_ID, EVENT_ID, TOKEN, PRODUCT_ID)))
                .isInstanceOf(ResponseStatusException.class);

        verify(redis, never()).delete(TOKEN_KEY);
    }
}
