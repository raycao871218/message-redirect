CREATE TABLE IF NOT EXISTS sms_messages (
    device_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sender VARCHAR(256) NOT NULL,
    body LONGTEXT NOT NULL,
    sent_at DATETIME(6) NOT NULL,
    captured_at DATETIME(6) NOT NULL,
    source VARCHAR(32) NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (device_id, message_id),
    INDEX sms_messages_sent_at_idx (sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
