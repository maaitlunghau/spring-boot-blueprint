package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class UserNotBannedException extends AppException {

    public UserNotBannedException(String identifier) {
        super(
            HttpStatus.CONFLICT,
            String.format("User is not banned: %s", identifier)
        );
    }
}
