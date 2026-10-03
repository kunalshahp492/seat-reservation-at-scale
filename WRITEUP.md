# Seat Reservation at Scale — Engineering Write-up

## Scope and contract

This service reserves named seats for a show through a Spring Boot 4 / Java 17 API. PostgreSQL 17 is the single source of truth. A booking contains one to four seats by default, and the limit is **four active seats per user per show across all bookings**, not four per HTTP request. A show can choose another positive `per_user_limit`. A confirmed reservation records an integer `amount_paise`; this exercise does not charge a payment method or integrate a gateway. The guest JWT endpoint gives reviewers distinct users for load tests, and a locally minted ADMIN JWT creates shows. No JWT signing secret or ADMIN token is committed.

## Atomic reservation decision

`ReservationService.reserve` runs in a database transaction. It validates and sorts the requested seat labels, hashes the show ID plus canonical labels, and calculates `price_paise × seat_count` with checked 64-bit multiplication. It then attempts an insert into `idempotency_requests` under the unique `(user_id, idempotency_key)` constraint. PostgreSQL makes a concurrent duplicate wait for the first transaction's result. A repeat with the same fingerprint reads the committed outcome; different input returns `409 idempotency_conflict`.

For a new key, the service inserts or locks `show_user_state(show_id,user_id)` with `SELECT ... FOR UPDATE`. That one row serializes bookings and cancellations by the same user for the same show. It checks the total active seat count before attempting any seat claim. If the limit is exceeded, it records `409 per_user_limit` in the idempotency row and commits the decline.

For an eligible request, it creates a savepoint, inserts an uncommitted reservation, and visits seat labels in ascending order. Each claim executes a conditional update equivalent to:

```sql
UPDATE show_seats
SET state = 'CONFIRMED', reservation_id = :reservation_id
WHERE show_id = :show_id AND seat_label = :seat_label AND state = 'AVAILABLE';
```

Exactly one concurrent transaction can change a given seat from `AVAILABLE`. If any update affects zero rows, the service rolls back to the savepoint. This removes the new reservation, seat links, and all earlier claims from that request. It then checks whether the failed label exists, records either `409 seat_taken` or `404 seat_not_found` in the idempotency row, and commits that outcome. A successful set of claims increments `active_seat_count`, records the reservation ID and `201`, and commits. There is no interval where a partial multi-seat reservation is committed.

The lock order is: idempotency key, user-state row, then seat rows in sorted order. There is no Redis lock or in-memory seat authority. The response body of a same-key successful replay points to the original reservation and returns `200`; a replay of a recorded decline returns its stored status and reason. Replay counts are durable. If the original reservation was later cancelled, its replay returns the cancelled state and never books seats again.

## Cancellation

Cancellation checks that the verified JWT subject owns the reservation. It locks the user's show state, then its seat rows in sorted label order, then the reservation row. Each release update requires the expected `reservation_id`, so a stale cancellation cannot release a later owner's seat. The same transaction changes the reservation to `CANCELLED` and subtracts its seat count once. A repeated owner cancellation reads the already-cancelled state and does not decrement again. Historical reservations, seat links, and idempotency outcomes remain available for audit and replay.

## Availability, partition, and cost trade-offs

Every reservation write and capacity count is decided by PostgreSQL. When the API cannot reach PostgreSQL, `/health/ready` returns `503` and reservation writes fail. The service favors consistent ownership over accepting requests during a database partition. `/health/live` stays independent of PostgreSQL so an orchestrator can distinguish a responsive JVM from a ready service. This single-primary design does not provide cross-region write availability; a production version would need a tested failover policy and careful handling of idempotent retries across failover.

The schema stores one row per seat and one user-state row per active user/show pair. This makes the invariant and lock contention easy to inspect, at the cost of row-level contention on popular seats and extra writes for each requested seat. Sorted claims avoid cycles among requests that overlap multiple seats. A 10-connection Hikari pool bounds concurrent database work; the burst client also bounds in-flight HTTP requests at 64 so a small host is not overwhelmed by client-side socket creation.

## Operations and 2 a.m. response

`/actuator/prometheus` exposes `reservations_confirmed_total` from committed 201 outcomes, `reservations_declined_total{reason}` from committed decline reasons and replay counts, `idempotency_replays_total` from summed replay counts, and `seats_available` from live seat rows. ECS JSON request logs contain `request_id`, route, status, latency, outcome reason, and known show/reservation IDs, but omit JWTs, request bodies, and secrets. `X-Request-Id` is returned to the caller.

At 2 a.m. I would page on sustained `/health/ready` failures, nonzero 5xx rate, and a sustained rise in request latency. I would alert on the seat-count reconciliation invariant (`available + held + confirmed = total`) or any duplicate seat owner, because these signal correctness loss. A high `seat_taken` rate alone is expected during a hot-seat burst and should be a dashboard signal rather than a page. Incident triage starts with the request ID in JSON logs, then PostgreSQL connectivity and pool saturation, transaction errors, and the affected show/reservation IDs. If the database is unavailable, restore the database or fail over through a tested procedure; do not accept speculative reservations.

## Verification and measured evidence

PostgreSQL-backed CI covers a 500-user race for one seat, overlapping two-seat requests, ten simultaneous bookings by one user, same-key replay/conflict, invalid input with no state change, cancellation ownership/repeat/race, health, metrics, and logs. The CI burst smoke issued 24 measured reserve calls and observed `new_confirmed=2`, `replay=2`, `seat_taken=18`, `per_user_limit=2`, `other_4xx=0`, `5xx=0`, `transport_errors=0`, and `reconciliation=PASS`. The hot-seat scenario had exactly one new winner. CI also built the production Docker image. The Windows Maven wrapper and a clean local Maven build both passed.

The live Render URL is https://seat-reservation-at-scale-h295.onrender.com. Its first deploy from commit `e1fc70d` reached a live state in 2 minutes 44 seconds; both `/health/live` and the database-backed `/health/ready` returned HTTP 200. A 100-request live burst completed in 8,784 ms with 2 new confirmations, 10 replays, 78 `seat_taken`, 10 `per_user_limit`, zero 5xx or transport errors, one hot-seat winner, and reconciliation PASS. The public Prometheus endpoint then showed 4 confirmed outcomes (including two seed reservations), 10 replays, 78 seat-taken declines, 10 limit declines, and 2 available seats across those four shows. Render application logs showed JSON `409` decline entries with `request_id`, route, reason, and latency. A short recording of those live logs is in `docs/evidence/render-live-logs.mp4`; the Render dashboard itself requires workspace access.

The first 20,000-request live run took 664,331 ms and ended with 2 new confirmations, 2,000 replays, 15,997 `seat_taken`, 1,999 `per_user_limit`, zero other 4xx, **two HTTP 520 responses**, and zero transport errors. Every show's final count reconciled, and the hot seat had exactly one new winner. Durable server counters showed one additional `seat_taken` and one additional `per_user_limit` compared with client-observed declines, so both 520 requests reached the application and were recorded as domain declines. This run **did not meet the zero-5xx bar**.

A second 20,000-request live run with the same 64-request in-flight window took 573,538 ms and passed: 2 new confirmations, 2,000 replays, 15,998 `seat_taken`, 2,000 `per_user_limit`, zero other 4xx, **zero 5xx**, zero transport errors, exactly one new hot-seat winner, and reconciliation PASS on all four shows. The server counters matched those outcomes. The diagnostic client was prepared to print response headers for any repeated 520, but none occurred; the exact layer responsible for the first run's two 520s remains unproven. The free hosting tier and its ingress remain a reliability risk for larger external bursts. Retrying an uncertain response with the same idempotency key is safe because the database stores the committed outcome.

## AI usage and follow-up work

### How AI was used

I used Codex as an active engineering assistant throughout the assignment. I directed the requirements and selected the key product and architectural constraints: Java 17 with Spring Boot, JWT-based identity, PostgreSQL as the system of record, a four-active-seat limit across all bookings for one user and show, all-or-nothing multi-seat reservations, explicit owner cancellation, and deployment on Render.

Codex contributed substantially to the implementation. It analyzed requirement ambiguities, proposed the database transaction and lock order, generated application and test code, and helped set up Flyway migrations, Docker, GitHub Actions, the burst client, metrics, structured logging, deployment steps, and documentation drafts. It also inspected CI and live-run failures and proposed fixes. I reviewed and approved the requirements and design choices, handled GitHub and Render authorization and credentials, observed the live runs, recorded the logs, and decided which evidence and limitations to include in the submission.

**Directed vs decided:** I directed the system requirements, technology stack, scope, and accepted trade-offs. Codex selected many low-level implementation details within that approved design, including the repository structure, SQL and JDBC mechanics, test organization, CI steps, and diagnostic workflow. I accepted those suggestions only after verification through failing-then-passing tests, PostgreSQL-backed CI, Docker builds, health and metrics checks, concurrency bursts, and a fresh whole-branch review. That review found three issues—cross-collation cancellation lock ordering, fractional numeric coercion, and exception-path logs reporting HTTP 200—and each was fixed with a regression test before the final successful CI run.

The evidence intentionally includes the observed failure. The first 20,000-request live run produced two HTTP 520 responses even though the database state reconciled. A second run of the same size completed with zero 5xx responses. Both results are reported above, and the unresolved free-tier ingress risk remains disclosed.

Next improvements would include a more realistic authentication and ADMIN provisioning path, rate limits around public guest tokens and metric scraping, a dedicated load environment with repeatable latency percentiles, database connection and lock-wait dashboards, PostgreSQL backup/failover drills, and an explicit payment workflow with its own idempotent state machine. Timed holds were excluded from this version, so `held` is always zero.
