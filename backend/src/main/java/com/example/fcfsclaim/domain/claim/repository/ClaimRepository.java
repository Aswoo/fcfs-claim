package com.example.fcfsclaim.domain.claim.repository;

import com.example.fcfsclaim.domain.claim.entity.Claim;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    boolean existsByEventIdAndUserId(Long eventId, Long userId);
}
