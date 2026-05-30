CREATE TABLE orders (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      VARCHAR(255) NOT NULL,
    status       VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    total_amount NUMERIC(10,2) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE order_items (
    id         BIGSERIAL      PRIMARY KEY,
    order_id   UUID           NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id VARCHAR(255)   NOT NULL,
    quantity   INT            NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(10,2)  NOT NULL CHECK (unit_price >= 0)
);
