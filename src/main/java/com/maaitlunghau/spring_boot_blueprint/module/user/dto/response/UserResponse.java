package com.maaitlunghau.spring_boot_blueprint.module.user.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.maaitlunghau.spring_boot_blueprint.module.user.entity.Role;

public record UserResponse(
        UUID id,
        String fullName,
        String email,
        Role role,
        boolean enabled,
        String imageUrl,
        LocalDateTime createdAt) {
}
