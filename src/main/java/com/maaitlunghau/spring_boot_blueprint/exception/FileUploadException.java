package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class FileUploadException extends AppException {

    public FileUploadException(String message) {
        super(
            HttpStatus.BAD_GATEWAY,
            message
        );
    }
}
