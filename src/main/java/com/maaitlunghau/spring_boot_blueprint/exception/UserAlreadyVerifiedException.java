package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class UserAlreadyVerifiedException extends AppException {

    public UserAlreadyVerifiedException(String identifier) {
        super(
            HttpStatus.CONFLICT,
            String.format("User email is already verified: %s", identifier)
        );
    }
}
