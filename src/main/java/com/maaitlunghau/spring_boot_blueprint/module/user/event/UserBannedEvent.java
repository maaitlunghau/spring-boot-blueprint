package com.maaitlunghau.spring_boot_blueprint.module.user.event;

import java.time.Instant;
import java.util.UUID;

public record UserBannedEvent(
    UUID userId,
    String email,
    String fullName,
    String reason,
    Instant bannedUntil
) {}
