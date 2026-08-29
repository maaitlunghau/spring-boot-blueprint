package com.maaitlunghau.spring_boot_blueprint.module.user.entity;

import java.util.Objects;

import org.hibernate.proxy.HibernateProxy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.maaitlunghau.spring_boot_blueprint.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;

@Entity
@Table(name = "users")
@Getter
public class User extends BaseEntity {

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;    

    @Column(name = "image_url", length = 1000, nullable = true)
    private String imageUrl;

    @Column(name = "image_public_id", nullable = true)
    private String imagePublicId;

    protected User() {
    }

    @Builder
    public User(
        String fullName,
        String email,
        String passwordHash,
        Role role,
        boolean enabled,
        String imageUrl,
        String imagePublicId
    ) {
        this.fullName = fullName;
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
        this.imageUrl = imageUrl;
        this.imagePublicId = imagePublicId;
    }

    public void updateProfile(String fullName) {
        this.fullName = fullName;
    }

    public void updateAvatar(String imageUrl, String imagePublicId) {
        this.imageUrl = imageUrl;
        this.imagePublicId = imagePublicId;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }

        Class<?> thisEffectiveClass = this instanceof HibernateProxy hibernateProxy
            ? hibernateProxy.getHibernateLazyInitializer().getPersistentClass()
            : this.getClass();
        Class<?> otherEffectiveClass = o instanceof HibernateProxy hibernateProxy
            ? hibernateProxy.getHibernateLazyInitializer().getPersistentClass()
            : o.getClass();

        if (thisEffectiveClass != otherEffectiveClass) {
            return false;
        }

        User other = (User) o;
        return email != null && Objects.equals(email, other.email);
    }

    @Override
    public int hashCode() {
        return this instanceof HibernateProxy hibernateProxy
            ? hibernateProxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
            : getClass().hashCode();
    }

    @Override
    public String toString() {
        return "User{id=%s, email=%s, role=%s}".formatted(getId(), email, role);
    }
}
