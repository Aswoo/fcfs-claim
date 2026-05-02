package com.example.fcfsclaim.domain.queue.repository;

import com.example.fcfsclaim.domain.queue.entity.QueueToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QueueTokenRepository extends JpaRepository<QueueToken, Long> {

    Optional<QueueToken> findByToken(String token);
}
