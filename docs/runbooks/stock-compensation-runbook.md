# Runbook — Stock Compensation Dead-Letter

## What is this?

When an order's payment succeeds but stock decrement partially fails, order-service attempts inline
stock compensation (incrementing already-decremented items back to their original counts). If this
inline compensation also fails, a `saga_compensation_steps` row is written and the `OrderRecoveryJob`
retries it automatically with exponential backoff.

**Automatic behaviour summary:**

| retryCount | What happens |
|---|---|
| 1–4 | Silent retry, next attempt in `min(2^n, 3600)` seconds |
| 5 | `saga.compensation.struggling.total` metric fires — investigate, but job continues retrying |
| 6–49 | Continues retrying with increasing backoff (max 1 hour between attempts) |
| 50 | `saga.compensation.dead_letter.total` metric fires, status set to `DEAD_LETTER` — **human decision required** |
| Any | If catalog-service returns gRPC `NOT_FOUND` — immediate `DEAD_LETTER`, product was deleted |

The job runs every 5 minutes. At 1-hour max backoff, 50 retries takes several weeks before dead-letter.
In practice, if catalog-service is down for any reason that resolves within hours, recovery is automatic.

---

## Alert: `saga.compensation.struggling.total`

**Meaning:** A compensation step has failed 5 times. The job is still retrying automatically, but
something is wrong that hasn't self-healed. No immediate action required unless the count keeps rising.

**Check:**
```sql
SELECT id, order_id, step_name, retry_count, last_attempted_at, status
FROM saga_compensation_steps
WHERE status IN ('PENDING', 'RETRYING')
ORDER BY retry_count DESC;
```

**Also check:** catalog-service health, ECS task status, CloudWatch logs for gRPC errors.

If the underlying issue is resolved (catalog-service restarted, network recovered), no manual action
needed — the job picks up and completes the step automatically.

---

## Alert: `saga.compensation.dead_letter.total`

**Meaning:** A step has been moved to `DEAD_LETTER`. Automatic retry stopped. Human decision required.

**Find the dead-lettered step:**
```sql
SELECT id, order_id, step_name, payload, retry_count, last_attempted_at
FROM saga_compensation_steps
WHERE status = 'DEAD_LETTER'
ORDER BY last_attempted_at DESC;
```

**Determine why it dead-lettered** from CloudWatch Logs (filter: `DEAD_LETTER`).

---

## Response procedures

### Case 1: Transient failure resolved (service was down, now recovered)

The step is stuck in `DEAD_LETTER` due to max retries, but the underlying issue is fixed.

**Resolution:** Reset the step to resume automatic retry.
```sql
UPDATE saga_compensation_steps
SET status = 'PENDING', retry_count = 0, last_attempted_at = NULL
WHERE id = '<step-id>';
```

The recovery job picks it up on the next 5-minute cycle and retries with fresh backoff.

### Case 2: Product deleted from catalog after order was placed

The step is `DEAD_LETTER` with `reason=product_not_found`. The product no longer exists in DynamoDB,
so incrementing stock is impossible.

**Impact:** The inventory count for this product will not be corrected. Since the product is deleted,
this is typically a non-issue — no future orders can reference it. However, if the deletion was
accidental, see Case 3.

**Resolution:** Acknowledge the discrepancy and close the step.
```sql
UPDATE saga_compensation_steps
SET status = 'ACCEPTED'
WHERE id = '<step-id>';
```

The customer's payment was refunded (saga compensation completed the refund before stock compensation
failed). The customer is made whole. The inventory discrepancy is written off.

**Optional:** If the product deletion was accidental and the product should be restored, recreate it
in the catalog, then reset the step as in Case 1.

### Case 3: Persistent gRPC failure with no clear cause

The step failed 50 times over several weeks. The gRPC status is `UNAVAILABLE` or `INTERNAL`.

**Steps:**
1. Check catalog-service logs for that time period.
2. Check the specific product in DynamoDB: does it exist? Is the stock attribute present?
   ```bash
   aws dynamodb get-item \
     --table-name portfolio-dev-catalog \
     --key '{"pk":{"S":"PRODUCT#<productId>"},"sk":{"S":"PRODUCT#<productId>"}}'
   ```
3. If the item exists and looks healthy, reset the step (Case 1) — likely a long outage that outlasted
   the retry window.
4. If the item is corrupt or missing data, fix it manually, then reset the step.
5. If the root cause cannot be determined, manually increment the stock in DynamoDB and set the step
   to ACCEPTED:
   ```bash
   aws dynamodb update-item \
     --table-name portfolio-dev-catalog \
     --key '{"pk":{"S":"PRODUCT#<productId>"},"sk":{"S":"PRODUCT#<productId>"}}' \
     --update-expression "SET stock = stock + :qty" \
     --expression-attribute-values '{":qty":{"N":"<quantity>"}}'
   ```
   ```sql
   UPDATE saga_compensation_steps SET status = 'ACCEPTED' WHERE id = '<step-id>';
   ```

---

## Payload format

The `payload` column is JSON. Decode it to understand what operation failed:

```json
{ "productId": "abc123", "quantity": 2 }
```

This means: `incrementStock("abc123", 2)` failed — the step should add 2 units back to product abc123.

---

## Known limitation

`incrementStock` is not idempotent. If a step is reset to PENDING (Case 1 resolution) after the
increment was actually applied but the DB status update failed (crash between gRPC success and
`markCompleted`), the stock will be over-incremented by `quantity`. This is rare (requires a crash
in a ~10ms window) and the discrepancy is bounded to the order's quantity. Monitor with periodic
inventory reconciliation in production.
