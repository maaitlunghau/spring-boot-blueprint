package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class EmailPendingPurgeException extends AppException {

    public EmailPendingPurgeException() {
        super(
            HttpStatus.CONFLICT,
            "This email cannot be used for registration at this time. Please contact an administrator for assistance."
        );
    }
}
