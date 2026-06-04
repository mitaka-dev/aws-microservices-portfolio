CREATE TABLE payment_records (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id       VARCHAR(255) NOT NULL,
    amount         NUMERIC(10,2) NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    method         VARCHAR(50)  NOT NULL,
    status         VARCHAR(50)  NOT NULL,
    failure_reason VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_records_order_id ON payment_records (order_id);
CREATE INDEX idx_payment_records_status   ON payment_records (status);
