package com.maaitlunghau.spring_boot_blueprint.module.user.event;

import java.util.UUID;

public record EmailVerificationOtpEvent(
    UUID userId,
    String email,
    String fullName,
    String otp
) {}
