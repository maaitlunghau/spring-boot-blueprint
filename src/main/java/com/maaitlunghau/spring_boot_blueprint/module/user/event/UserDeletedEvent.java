package com.maaitlunghau.spring_boot_blueprint.module.user.event;

import java.util.UUID;

public record UserDeletedEvent(
    UUID userId,
    String email,
    String fullName
) {}
