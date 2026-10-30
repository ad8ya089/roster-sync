CREATE TABLE processed_event (
    sink VARCHAR(60) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (sink, event_id)
);
CREATE INDEX ix_processed_event_retention ON processed_event (processed_at);
