package com.example.fcfsclaim.domain.queue.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "queue_token",
    uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "user_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QueueToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 36)
    private String token;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TokenStatus status;

    public static QueueToken of(Long eventId, Long userId, String token) {
        QueueToken qt = new QueueToken();
        qt.eventId = eventId;
        qt.userId = userId;
        qt.token = token;
        qt.issuedAt = LocalDateTime.now();
        qt.expiresAt = qt.issuedAt.plusSeconds(300);
        qt.status = TokenStatus.VALID;
        return qt;
    }

    public void markUsed() {
        this.status = TokenStatus.USED;
    }
}
