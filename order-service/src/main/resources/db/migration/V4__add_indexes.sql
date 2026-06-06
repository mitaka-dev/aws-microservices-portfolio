CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_user_id_status ON orders(user_id, status);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
