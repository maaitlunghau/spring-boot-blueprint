package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class UserNotDeletedException extends AppException {

    public UserNotDeletedException(String identifier) {
        super(
            HttpStatus.CONFLICT,
            String.format("User is not deleted: %s", identifier)
        );
    }
}
