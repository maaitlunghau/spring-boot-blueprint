package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class RefreshTokenReuseException extends AppException {

    public RefreshTokenReuseException() {
        super(
            HttpStatus.UNAUTHORIZED,
            "Refresh token has been reused"
        );
    }
}
