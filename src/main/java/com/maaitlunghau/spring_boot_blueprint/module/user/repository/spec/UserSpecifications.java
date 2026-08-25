package com.maaitlunghau.spring_boot_blueprint.module.user.repository.spec;

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
}
