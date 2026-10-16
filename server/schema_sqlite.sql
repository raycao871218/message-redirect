CREATE TABLE IF NOT EXISTS sms_messages (
    device_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    sender TEXT NOT NULL,
    body TEXT NOT NULL,
    sent_at TEXT NOT NULL,
    captured_at TEXT NOT NULL,
    source TEXT NOT NULL,
    received_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    payload_hash TEXT NOT NULL,
    read_at TEXT NULL,
    PRIMARY KEY (device_id, message_id)
);
CREATE INDEX IF NOT EXISTS sms_messages_sent_at_idx ON sms_messages (sent_at DESC);
