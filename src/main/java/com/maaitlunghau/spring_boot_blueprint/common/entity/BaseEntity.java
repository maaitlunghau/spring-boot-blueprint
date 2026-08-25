package com.maaitlunghau.spring_boot_blueprint.common.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.maaitlunghau.spring_boot_blueprint.common.persistence.annotation.HasUuidV7;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;

@MappedSuperclass
@Getter
public abstract class BaseEntity {

    @Id
    @HasUuidV7
    @Column(updatable = false, nullable = false)
    private UUID id;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
