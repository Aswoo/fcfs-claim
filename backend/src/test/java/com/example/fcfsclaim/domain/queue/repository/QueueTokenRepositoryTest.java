package com.example.fcfsclaim.domain.queue.repository;

import com.example.fcfsclaim.domain.queue.entity.QueueToken;
import com.example.fcfsclaim.domain.queue.entity.TokenStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class QueueTokenRepositoryTest {

    @Autowired QueueTokenRepository queueTokenRepository;
    @Autowired TestEntityManager em;

    private long userIdSeq = 1L;

    private QueueToken saveWithExpiry(LocalDateTime expiresAt) {
        QueueToken qt = QueueToken.of(1L, userIdSeq++, UUID.randomUUID().toString());
        ReflectionTestUtils.setField(qt, "expiresAt", expiresAt);
        return queueTokenRepository.save(qt);
    }

    // ── expireOverdue ────────────────────────────────────────────────────

    @Test
    void expireOverdue_만료된_토큰만_EXPIRED_처리() {
        LocalDateTime now = LocalDateTime.now();
        QueueToken expired = saveWithExpiry(now.minusSeconds(1));  // 만료됨
        QueueToken valid   = saveWithExpiry(now.plusSeconds(300)); // 유효

        int count = queueTokenRepository.expireOverdue(TokenStatus.VALID, TokenStatus.EXPIRED, now);
        em.clear(); // @Modifying JPQL 후 1차 캐시 무효화

        assertThat(count).isEqualTo(1);
        assertThat(queueTokenRepository.findById(expired.getId()).orElseThrow().getStatus())
                .isEqualTo(TokenStatus.EXPIRED);
        assertThat(queueTokenRepository.findById(valid.getId()).orElseThrow().getStatus())
                .isEqualTo(TokenStatus.VALID);
    }

    @Test
    void expireOverdue_만료_토큰_없으면_0반환() {
        LocalDateTime now = LocalDateTime.now();
        saveWithExpiry(now.plusSeconds(300));

        int count = queueTokenRepository.expireOverdue(TokenStatus.VALID, TokenStatus.EXPIRED, now);

        assertThat(count).isEqualTo(0);
    }

    @Test
    void expireOverdue_이미_USED인_토큰은_건드리지_않음() {
        LocalDateTime now = LocalDateTime.now();
        QueueToken used = saveWithExpiry(now.minusSeconds(1));
        used.markUsed();
        queueTokenRepository.save(used);

        int count = queueTokenRepository.expireOverdue(TokenStatus.VALID, TokenStatus.EXPIRED, now);
        em.clear();

        assertThat(count).isEqualTo(0);
        assertThat(queueTokenRepository.findById(used.getId()).orElseThrow().getStatus())
                .isEqualTo(TokenStatus.USED);
    }

    @Test
    void expireOverdue_복수_만료_토큰_모두_처리() {
        LocalDateTime now = LocalDateTime.now();
        saveWithExpiry(now.minusSeconds(1));
        saveWithExpiry(now.minusSeconds(1));
        saveWithExpiry(now.plusSeconds(300));

        int count = queueTokenRepository.expireOverdue(TokenStatus.VALID, TokenStatus.EXPIRED, now);

        assertThat(count).isEqualTo(2);
    }
}
