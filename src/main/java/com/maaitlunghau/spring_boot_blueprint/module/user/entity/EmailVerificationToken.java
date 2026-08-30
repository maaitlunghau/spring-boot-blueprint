package com.maaitlunghau.spring_boot_blueprint.module.user.entity;

import java.time.Instant;
import java.util.UUID;

import com.maaitlunghau.spring_boot_blueprint.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;

@Entity
@Table(name = "email_verification_tokens")
@Getter
public class EmailVerificationToken extends BaseEntity {

    private static final int MAX_ATTEMPTS = 5;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at", nullable = true)
    private Instant usedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    protected EmailVerificationToken() {
    }

    @Builder
    public EmailVerificationToken(UUID userId, String otpHash, Instant expiresAt) {
        this.userId = userId;
        this.otpHash = otpHash;
        this.expiresAt = expiresAt;
        this.attemptCount = 0;
    }

    public void markUsed() {
        this.usedAt = Instant.now();
    }

    public void incrementAttempt() {
        this.attemptCount++;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isAttemptsExceeded() {
        return attemptCount >= MAX_ATTEMPTS;
    }
}
