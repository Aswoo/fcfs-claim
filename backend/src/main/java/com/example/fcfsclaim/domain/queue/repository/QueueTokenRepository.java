package com.example.fcfsclaim.domain.queue.repository;

import com.example.fcfsclaim.domain.queue.entity.QueueToken;
import com.example.fcfsclaim.domain.queue.entity.TokenStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface QueueTokenRepository extends JpaRepository<QueueToken, Long> {

    Optional<QueueToken> findByToken(String token);

    @Modifying
    @Query("UPDATE QueueToken q SET q.status = :expired WHERE q.status = :valid AND q.expiresAt < :now")
    int expireOverdue(@Param("valid") TokenStatus valid,
                      @Param("expired") TokenStatus expired,
                      @Param("now") LocalDateTime now);
}
