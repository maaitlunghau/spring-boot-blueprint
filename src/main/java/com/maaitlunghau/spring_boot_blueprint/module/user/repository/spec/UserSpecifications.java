package com.maaitlunghau.spring_boot_blueprint.module.user.repository.spec;

import java.time.Instant;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import com.maaitlunghau.spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.User;

public class UserSpecifications {

    private UserSpecifications() {}

    public static Specification<User> keywordIn(String keyword) {
        return (root, query, cb) -> {
            if (!StringUtils.hasText(keyword)) return cb.conjunction();

            String pattern = "%" + keyword.toLowerCase() + "%";

            return cb.or(
                cb.like(cb.lower(root.get("fullName")), pattern),
                cb.like(cb.lower(root.get("email")), pattern)
            );
        };  
    }

    public static Specification<User> hasRole(Role role) {
        return (root, query, cb) -> {
            if (role == null) return cb.conjunction();
            return cb.equal(root.get("role"), role);
        };
    }

    public static Specification<User> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<User> onlyDeleted() {
        return (root, query, cb) -> cb.isNotNull(root.get("deletedAt"));
    }

    public static Specification<User> isBanned(Boolean banned) {
        return (root, query, cb) -> {
            if (banned == null) return cb.conjunction();
            return cb.equal(root.get("enabled"), !banned);
        };
    }

    public static Specification<User> isEmailVerified(Boolean emailVerified) {
        return (root, query, cb) -> {
            if (emailVerified == null) return cb.conjunction();
            return cb.equal(root.get("emailVerified"), emailVerified);
        };
    }

    public static Specification<User> instantBetween(String field, Instant from, Instant to) {
        return (root, query, cb) -> {
            if (from == null && to == null) return cb.conjunction();
            if (from != null && to != null) return cb.between(root.get(field), from, to);
            if (from != null) return cb.greaterThanOrEqualTo(root.get(field), from);
            return cb.lessThanOrEqualTo(root.get(field), to);
        };
    }
}
