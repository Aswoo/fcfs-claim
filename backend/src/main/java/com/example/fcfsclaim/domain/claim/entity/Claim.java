package com.example.fcfsclaim.domain.claim.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "claim",
    uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "user_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 36)
    private String token;

    @Column(name = "claimed_at", nullable = false)
    private LocalDateTime claimedAt;

    public static Claim of(Long eventId, Long userId, String token) {
        Claim c = new Claim();
        c.eventId = eventId;
        c.userId = userId;
        c.token = token;
        c.claimedAt = LocalDateTime.now();
        return c;
    }
}
