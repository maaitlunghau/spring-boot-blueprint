package com.maaitlunghau.spring_boot_blueprint.module.user.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.maaitlunghau.spring_boot_blueprint.module.user.entity.User;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByEnabledFalseAndBannedUntilBefore(Instant instant);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    Optional<User> findByIdAndDeletedAtIsNotNull(UUID id);

    boolean existsByIdAndDeletedAtIsNull(UUID id);

    List<User> findByDeletedAtBefore(Instant cutoff);
}
