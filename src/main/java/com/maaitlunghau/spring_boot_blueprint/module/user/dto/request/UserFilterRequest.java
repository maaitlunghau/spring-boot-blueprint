package com.maaitlunghau.spring_boot_blueprint.module.user.dto.request;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;

import com.maaitlunghau.spring_boot_blueprint.module.user.entity.Role;

public record UserFilterRequest(
    String keyword,
    Role role,
    Boolean banned,
    Boolean emailVerified,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant bannedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant bannedAtTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant bannedUntilFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant bannedUntilTo
) {}
