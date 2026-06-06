-- Tracks stock increment compensation steps that failed inline during saga compensation.
-- The OrderRecoveryJob retries these with exponential backoff.
-- status values: PENDING, RETRYING, COMPLETED, DEAD_LETTER
-- Dead-letter fires a CloudWatch alarm; an engineer resets status to PENDING to resume retrying,
-- or sets ACCEPTED to acknowledge the discrepancy (e.g. product was deleted).
CREATE TABLE saga_compensation_steps (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id          UUID         NOT NULL REFERENCES orders(id),
    step_name         VARCHAR(50)  NOT NULL,
    payload           TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count       INT          NOT NULL DEFAULT 0,
    max_retries       INT          NOT NULL DEFAULT 50,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_attempted_at TIMESTAMPTZ
);

-- Partial index: only pending/retrying rows are queried by the recovery job.
CREATE INDEX idx_saga_compensation_pending
    ON saga_compensation_steps(status, created_at)
    WHERE status IN ('PENDING', 'RETRYING');
