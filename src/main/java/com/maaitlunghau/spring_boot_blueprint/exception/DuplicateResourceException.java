package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class DuplicateResourceException extends AppException {

    public DuplicateResourceException(String resource, String identifier) {
        super(
            HttpStatus.CONFLICT,
            String.format("Duplicate resource: %s with identifier: %s", resource, identifier)
        );
    }
}
