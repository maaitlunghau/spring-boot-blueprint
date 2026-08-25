package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class InvalidRefreshTokenException extends AppException {

    public InvalidRefreshTokenException() {
        super(
            HttpStatus.UNAUTHORIZED,
            "Invalid refresh token"
        );
    }
}
