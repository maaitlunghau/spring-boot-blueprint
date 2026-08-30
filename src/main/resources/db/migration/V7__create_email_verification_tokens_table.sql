CREATE TABLE email_verification_tokens (
    id BINARY(16) NOT NULL PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    otp_hash VARCHAR(255) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) DEFAULT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    version BIGINT NOT NULL,
    CONSTRAINT fk_email_verification_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
