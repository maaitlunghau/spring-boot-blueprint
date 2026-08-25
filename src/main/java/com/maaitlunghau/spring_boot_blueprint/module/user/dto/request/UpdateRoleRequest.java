package com.maaitlunghau.spring_boot_blueprint.module.user.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateRoleRequest(
    @NotBlank(message = "Role is required")
    String role
) {}
