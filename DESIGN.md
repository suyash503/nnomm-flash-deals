# Design

Every rule that has to hold across two instances is enforced by PostgreSQL, through row locks, a partial unique index, primary keys and CHECK constraints. No application memory is involved. All time decisions use the database clock (`clock_timestamp()`), so the two instances can't disagree about whether a deal is live or a claim is due.

## 1. Two customers on different instances claim the last unit

Each request runs one transaction ([Claims.kt:43](src/main/kotlin/com/nnomm/deals/Claims.kt#L43)):

1. `INSERT INTO idempotency_keys ... ON CONFLICT DO NOTHING` (L55). The keys differ, so there's no conflict.
2. `INSERT INTO claims (..., 'RESERVED', ...)` (L96). The customers differ, so the partial unique index allows both.
3. `UPDATE deals SET stock_available = stock_available - 1 WHERE id = ? AND stock_available >= 1 AND <live>` (L112).

At step 3, A reaches the row first and takes its row lock. B finds the same row version, sees it locked by A's open transaction, and waits. When A commits (stock 0), B wakes up. Under READ COMMITTED, Postgres re-reads the **latest** version of the row and re-evaluates the WHERE clause. `0 >= 1` is false, so B's UPDATE touches 0 rows. B rolls back to its savepoint (L119), which removes its claim row, stores `409 SOLD_OUT` and commits. The `CHECK (stock_available BETWEEN 0 AND stock_total)` ([V1__init.sql:13](src/main/resources/db/migration/V1__init.sql#L13)) is a backstop if this logic ever breaks.

A Kotlin `Mutex` or `synchronized` block lives in one JVM's memory. The two instances are separate processes, each with its own mutex, so A and B would both pass. It also wouldn't cover the sweeper or webhooks, which change the same rows, and it would serialise every deal in that process behind one lock.

## 2. Crash after commit, or just before it

**After commit:** the claim, the stock decrement and the stored 201 committed together. The retry's `INSERT ... ON CONFLICT DO NOTHING` inserts nothing. We read the stored response and replay it with `Idempotent-Replayed: true` ([Claims.kt:60](src/main/kotlin/com/nnomm/deals/Claims.kt#L60)). No second claim is created.

**Before commit:** nothing became visible. When the process dies, its socket closes and Postgres rolls back the whole transaction: the key row, the claim and the decrement. The retry inserts the key fresh and claims once. A retry arriving *while* the dead transaction still looks open waits on the key's row lock for up to 5 s (`SET LOCAL lock_timeout`, L53), then gets `409 REQUEST_IN_PROGRESS`. If a connection hangs instead of closing, `idle_in_transaction_session_timeout = 30s` ([Db.kt:45](src/main/kotlin/com/nnomm/deals/Db.kt#L45)) and the TCP keepalives in `docker-compose.yml` end it, so a key is never blocked for 60 s.

## 3. Payment webhook and expiry hit one claim in the same millisecond

Both need the claim's row lock. The webhook uses `SELECT ... FOR UPDATE` ([Payments.kt:80](src/main/kotlin/com/nnomm/deals/Payments.kt#L80)). The sweeper uses `FOR UPDATE SKIP LOCKED` ([Expiry.kt:34](src/main/kotlin/com/nnomm/deals/Expiry.kt#L34)). Let `q` be the claim's units, held since the claim.

| Ordering | Final status | Stock |
|---|---|---|
| Webhook locks first, its clock is before `expires_at`, amount matches | PAID | stays held (no change) |
| Same, amount differs | NEEDS_REVIEW | stays held |
| Webhook locks first, its clock is at or after `expires_at` (sweeper hasn't run) | REFUND_PENDING, set by the webhook (L94) | `+q` once, by the webhook |
| Sweeper locks first (claim due) | EXPIRED, then the webhook gets the lock, sees EXPIRED, sets REFUND_PENDING (L106) | `+q` once, by the sweeper; the webhook adds nothing |
| Sweeper runs while the webhook holds the lock | SKIP LOCKED skips the claim; the webhook decides as in rows 1–3; the next sweep sees a non-RESERVED claim and leaves it | as above |
| Sweeper runs just before `expires_at` | the claim isn't due, so the sweeper ignores it; the webhook decides | as above |
| The same event twice, concurrently | the second waits on the `payment_events` primary key, then inserts nothing → `DUPLICATE_EVENT` | no change |

The webhook re-reads the claim *after* taking the lock (L85), so "past expires_at" is judged at processing time, not before a lock wait. Units are added back only by the transaction that moves a claim out of RESERVED, and only one transaction can do that. `WebhookTest` aims 80 payments at the expiry instant and checks `stock_available + held == stock_total`.

## 4. Webhook signatures

The HMAC is over **bytes**. If we parsed the JSON and re-serialised it, key order, whitespace and number formatting could change, and valid signatures would fail. Worse, two different byte strings can parse to the same object. `{"amount_paise":1,"amount_paise":19800}` parses to the last value, so checking a re-serialised form could accept bytes the provider never signed. We read the body once ([Http.kt](src/main/kotlin/com/nnomm/deals/Http.kt)), verify those bytes, and only then parse them ([Payments.kt:38](src/main/kotlin/com/nnomm/deals/Payments.kt#L38)).

The timestamp is inside the signed message, so it can't be edited. Rejecting anything more than 300 s off (L48) means a captured request can't be replayed later. The `event_id` dedupe would catch an exact replay too, but the time window doesn't depend on keeping every event forever. Comparison uses `MessageDigest.isEqual` (L54). A normal `==` stops at the first wrong byte, so response times reveal how many leading bytes were right, and an attacker can forge a signature one byte at a time.

## 5. Isolation level

READ COMMITTED, the Postgres default. It's enough because no decision reads a value and then writes based on that read in a separate step:
- stock is checked *inside* the `UPDATE ... WHERE stock_available >= ?`, which re-checks the newest row version after waiting for a lock;
- "one active claim" and "one use of a key or event" are unique constraints, enforced at insert;
- the webhook locks the claim row with `FOR UPDATE` before reading what it decides on.

A weaker approach would be `SELECT stock_available`, check `>= q` in Kotlin, then `UPDATE ... SET stock_available = <new value>`. That gives a **lost update**: two transactions both read 1, both write 0, and two claims hold one unit. The read-then-insert version of rule 2 ("SELECT active claim; if none, INSERT") is **write skew**: both see none and both insert. SERIALIZABLE would also be correct, but on a hot deal it turns lock waits into `40001` failures that every caller has to retry.

## 6. The constraint doing the most work

The partial unique index `claims_one_active_per_customer` ([V1__init.sql:31](src/main/resources/db/migration/V1__init.sql#L31)). It is the *only* thing enforcing rule 2; there is deliberately no "already claimed?" pre-check in code, because a pre-check races. It also makes a customer's concurrent claims with different keys queue behind each other. If it's dropped, the 20 concurrent claims in `ConcurrencyTest` all succeed and one customer holds 20 units. Expired claims also stop freeing the slot correctly, because the index only covers active statuses. A close second is the primary key `(customer_id, key)` on `idempotency_keys`. Without it, concurrent retries all run, and the customer gets 201s and 409s for one logical request instead of one replayed answer.

## 7. A deal goes viral: 100× claim traffic

**What fails first:** every claim on one deal queues on that deal's single row lock (Q1), held from the `UPDATE` until commit. Throughput per deal is about 1 / lock-hold time, whatever the number of instances. Waiting requests each hold a pooled connection (20 per instance, [Db.kt:41](src/main/kotlin/com/nnomm/deals/Db.kt#L41)), so the pools drain. New requests then wait 30 s for a connection and fail with 500s, and other deals suffer too.

**What I'd change, in order:**
1. Answer `SOLD_OUT` without a transaction once stock is 0, using a short cached read. Most of a viral spike arrives after the stock is gone.
2. Cut round trips while holding the lock. Do the claim in one SQL statement or function, and only run the per-deal expiry pass ([Claims.kt:30](src/main/kotlin/com/nnomm/deals/Claims.kt#L30)) on an `ALREADY_CLAIMED` or `SOLD_OUT` result, not on every request.
3. Split a hot deal's stock across N bucket rows; a claim decrements a random bucket that has stock. That gives N row locks instead of one.
4. Put PgBouncer in front of Postgres, and add per-customer rate limiting (a stretch goal) so retry storms don't multiply the load.
