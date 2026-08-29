package com.maaitlunghau.spring_boot_blueprint.module.user.dto.request;

import java.time.Instant;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;

public record BanUserRequest(
    @NotBlank(message = "Reason is required")
    String reason,

    @Future(message = "Banned until must be in the future")
    Instant bannedUntil
) {}
