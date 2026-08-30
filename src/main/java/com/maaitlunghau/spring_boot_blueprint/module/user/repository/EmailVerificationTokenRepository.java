package com.maaitlunghau.spring_boot_blueprint.module.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.maaitlunghau.spring_boot_blueprint.module.user.entity.EmailVerificationToken;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findTopByUserIdOrderByCreatedAtDesc(UUID userId);
}
