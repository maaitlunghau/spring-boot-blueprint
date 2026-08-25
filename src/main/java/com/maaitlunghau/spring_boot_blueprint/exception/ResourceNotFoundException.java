package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends AppException {

    public ResourceNotFoundException(String resource, String identifier) {
        super(
            HttpStatus.NOT_FOUND,
            String.format("Resource not found: %s with identifier: %s", resource, identifier)
        );
    }
}