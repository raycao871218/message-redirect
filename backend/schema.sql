CREATE SCHEMA IF NOT EXISTS message_redirect;
CREATE TABLE IF NOT EXISTS message_redirect.sms_messages (
    device_id uuid NOT NULL,
    message_id uuid NOT NULL,
    sender text NOT NULL,
    body text NOT NULL,
    sent_at timestamptz NOT NULL,
    captured_at timestamptz NOT NULL,
    source text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    payload_hash text NOT NULL,
    PRIMARY KEY (device_id, message_id)
);
CREATE INDEX IF NOT EXISTS sms_messages_sent_at_idx ON message_redirect.sms_messages (sent_at DESC);
