package com.maaitlunghau.spring_boot_blueprint.module.user.event;

import java.util.UUID;

public record UserUnbannedEvent(
    UUID userId,
    String email,
    String fullName
) {}
