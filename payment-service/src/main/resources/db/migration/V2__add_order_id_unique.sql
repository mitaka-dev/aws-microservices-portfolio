-- Enforce exactly-once payment per order: duplicate processPayment calls
-- with the same order_id return the existing record rather than double-charging.
ALTER TABLE payment_records
    ADD CONSTRAINT uq_payment_records_order_id UNIQUE (order_id);
