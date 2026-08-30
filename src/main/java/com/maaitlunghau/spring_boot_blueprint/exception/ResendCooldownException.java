package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class ResendCooldownException extends AppException {

    public ResendCooldownException(long secondsRemaining) {
        super(
            HttpStatus.TOO_MANY_REQUESTS,
            String.format("Please wait %d seconds before requesting another code", secondsRemaining)
        );
    }
}
