ALTER TABLE users
    ADD COLUMN email_verified BIT(1) NOT NULL DEFAULT b'0',
    ADD COLUMN email_verified_at DATETIME(6) DEFAULT NULL;
