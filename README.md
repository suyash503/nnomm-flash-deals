# Nnomm flash-deal claim service

A Kotlin (Ktor) + PostgreSQL service where restaurants run flash deals and customers claim units. It stays correct when requests race, clients retry, and payment webhooks arrive twice or out of order, with two instances sharing one database. How and why it works, with SQL and line numbers, is in [DESIGN.md](DESIGN.md).

## Run it

```bash
cp .env.example .env      # optional: set your own secrets; the compose file has local-only defaults
docker compose up --build
```

Postgres starts, then two instances of the service on **8080** and **8081**, both on the same database. Migrations (Flyway, `src/main/resources/db/migration`) run at startup. Both instances may run them at once safely, because Flyway takes a database lock.

Configuration is through environment variables: `DATABASE_URL` (a JDBC URL, or `postgres://...`, which is converted), `DATABASE_USER`, `DATABASE_PASSWORD`, `JWT_SECRET`, `WEBHOOK_SECRET`, `CLAIM_TTL_SECONDS` (default 600) and `PORT` (default 8080).

`python scripts/smoke.py` runs a quick end-to-end check against the running stack. It creates a deal on 8080, claims on 8081, replays the claim, pays for it, and reads everything back.

## Test it

```bash
./gradlew test
```

Needs Docker running and JDK 17+. The suite starts a real PostgreSQL 16 with Testcontainers, then **two real server instances** against it, and alternates requests between them. Nothing is mocked. 32 tests, about a minute:

| Brief requirement | Test |
|---|---|
| 200+ concurrent claims never oversell 50 units | `ConcurrencyTest`: 250 claims, exactly 50 succeed; mixed quantities keep `available + held == total` |
| 20+ concurrent requests with one key → one claim | `ConcurrencyTest`: 20 requests, one claim, every 201 is the same claim |
| One customer, different keys → one claim | `ConcurrencyTest`: 20 keys, 1 × 201 and 19 × `ALREADY_CLAIMED` |
| Bad/stale signatures, duplicate and out-of-order events | `WebhookTest` |
| Payment and expiry racing keep the stock invariant | `WebhookTest`: 40 claims with a 2 s TTL, payments aimed at the expiry instant, each sent to both instances |
| Customers/admins can't read others' claims | `AuthAndValidationTest` |

Also covered: JWT `alg: none` / HS512 / tampering / no `exp`, roles, input limits, 413, idempotent replay across instances, 422 key reuse, replayed `SOLD_OUT`, keys scoped per customer, the live window, and expiry (shown as EXPIRED at once, units back, customer can claim again).

## Layout

| File | What it does |
|---|---|
| `V1__init.sql` | Schema. The CHECKs, the partial unique index and the primary keys enforce the invariants |
| `Claims.kt` | Claim transaction: idempotency key, claim insert, stock decrement, stored response |
| `Payments.kt` | Webhook signature check and the payment state machine |
| `Expiry.kt` | Sweeper: expires due claims and returns their units in one statement |
| `Auth.kt`, `Http.kt`, `Errors.kt` | JWT checks, strict body parsing and limits, the error shape |
| `Db.kt` | Connection pool, migrations, the `tx {}` helper, parameterised-SQL helpers |

## Assumptions (where the brief is silent)

- **A second `payment.succeeded` (new `event_id`) for a PAID, NEEDS_REVIEW or REFUND_PENDING claim** doesn't change the claim. Those states are settled; flipping them would make the result depend on event order. The event is stored with outcome `EXTRA_PAYMENT_TO_REFUND` and logged as a warning, so finance can refund the double charge.
- **An event for an unknown `claim_id`** gets `200`, is stored as `UNKNOWN_CLAIM` and is logged. Claim IDs only exist after we commit, so this isn't an event that arrived early, and retrying won't make the claim appear. A 4xx would make the provider retry forever. Someone was charged, though, so the event is kept for reconciliation.
- **A signed event of another `type`** gets `200` with `IGNORED_EVENT_TYPE` and isn't stored, for the same retry-forever reason.
- **A malformed but correctly signed event** (bad `claim_id`, missing field) gets `400 VALIDATION_FAILED`.
- **`payment.succeeded` on a RESERVED claim past `expires_at` that the sweeper hasn't reached yet:** the webhook expires the claim itself, returning the units once, and sets REFUND_PENDING. "Past expires_at" means at processing time by the database clock, not the event's `occurred_at`.
- **The order of claim checks** is auth (401) → role (403) → Idempotency-Key (400) → body size (413) → body (400) → deal exists (404) → live (409 `DEAL_NOT_LIVE`) → `ALREADY_CLAIMED` → `SOLD_OUT`.
- **Idempotency records older than 24 hours** are forgotten when the key is reused. Same key and deal but a different quantity is a different request (422).
- **A request waiting on the same in-flight key** waits up to 5 s for the first one to finish and replays its result, then gives up with `409 REQUEST_IN_PROGRESS`.
- **Deals may be created with a window in the past.** Claims on them get `DEAL_NOT_LIVE`.
- **Title length** counts Unicode code points (1–100). Timestamps need an explicit zone; responses are in UTC.
- **Admins see a claim only if it's on their own restaurant's deal**, and anyone else gets 404. Customers may read any deal.
- **Every call to an endpoint that doesn't exist** gets a 404 in the standard error shape.

## What I'd do next

- The stretch goals: `GET /v1/me/claims` with keyset pagination on `(created_at, id)`, a 10-per-minute claim limit kept in Postgres so it holds across instances, JSON logs with request IDs, and `/metrics`.
- A job that prunes idempotency records older than 24 h, plus a reconciliation report for `UNKNOWN_CLAIM` and `EXTRA_PAYMENT_TO_REFUND` events.
- The hot-deal changes in DESIGN.md §7: an early `SOLD_OUT`, fewer round trips under the deal lock, bucketed stock.
- A load test (k6) against the two composed instances, measuring claim latency under contention.

## AI tools used

<!-- TODO before submitting: make this section true in your own words. -->
- **Claude Code (Anthropic):** wrote the code, tests, SQL and drafts of these docs, working from the brief, and ran the test suite against Postgres in Docker.
- Suggestions rejected during the build:
  - *Expiring a deal's due claims inside the claim transaction.* Rejected because it can deadlock. Transaction A would take the deal row lock while expiring, then wait on the unique index for B's uncommitted claim by the same customer. Meanwhile B waits for the deal row lock. That expiry now runs in its own short transaction before the claim ([Claims.kt:30](src/main/kotlin/com/nnomm/deals/Claims.kt#L30)).
  - *Returning 400 for webhook event types we don't handle.* Rejected because the provider retries anything that isn't 2xx, so a genuine signed event would be retried forever. It now gets 200 with `IGNORED_EVENT_TYPE`.
