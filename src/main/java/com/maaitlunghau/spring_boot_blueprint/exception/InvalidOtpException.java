package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class InvalidOtpException extends AppException {

    public InvalidOtpException() {
        super(HttpStatus.BAD_REQUEST, "Invalid or expired verification code");
    }
}
