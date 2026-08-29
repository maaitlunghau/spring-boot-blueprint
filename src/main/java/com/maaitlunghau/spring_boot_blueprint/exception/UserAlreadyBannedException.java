package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class UserAlreadyBannedException extends AppException {

    public UserAlreadyBannedException(String identifier) {
        super(
            HttpStatus.CONFLICT,
            String.format("User is already banned: %s", identifier)
        );
    }
}
