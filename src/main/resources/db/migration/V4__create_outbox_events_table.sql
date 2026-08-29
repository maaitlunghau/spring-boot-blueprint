CREATE TABLE outbox_events (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    retry_count INT NOT NULL,
    routing_key VARCHAR(100) NOT NULL,
    status ENUM('FAILED', 'PENDING', 'PUBLISHED') NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
