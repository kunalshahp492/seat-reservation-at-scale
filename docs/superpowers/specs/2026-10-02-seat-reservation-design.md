# Seat Reservation at Scale — Design

Date: 2026-10-02
Status: reviewed in conversation; implementation plan pending

## Purpose and success criteria

Build a Java Spring Boot JSON API for the Paytm Money take-home exercise. The service must make one atomic decision about each assigned seat, preserve a per-user limit of four active seats per show by default, and make retries safe. The evaluator will clone a public repository, deploy it, and send concurrency bursts to the live URL. A usable submission includes the containerized service, public deployment, burst command, health checks, Prometheus metrics, structured logs, README, and WRITEUP.md.

The agreed scope records integer amount_paise on reservations. It does not perform a real payment charge.

## Chosen approach and alternatives

Use PostgreSQL as the sole authority for reservations. Spring Boot handles HTTP, validation, JWT authentication, transaction boundaries, health, and metrics. Reservation decisions use explicit SQL so their lock order and conditional updates can be inspected.

Other viable approaches were considered:

- A unique active-claim table can enforce seat exclusivity, but cancellation and multi-seat rollback require additional state transitions.
- Redis locks add an operational dependency and do not replace a database constraint or transaction for durable correctness.

The PostgreSQL row approach has the fewest moving parts for this one-day assignment.

## Runtime and components

- Java 17, Spring Boot, Maven wrapper, PostgreSQL, Flyway migrations.
- REST controllers for shows, reservations, demo token issuance, health, and metrics.
- A reservation service owns all transaction rules; repositories execute SQL without making business decisions.
- Spring Security validates JWT signature, algorithm, issuer, audience, expiry, and role. The JWT subject is the sole user identity.
- A Dockerfile builds and runs the service. Compose starts the API and PostgreSQL locally. Render is the proposed public deployment target.

No frontend is planned.

## Data model

- shows: ID, name, price_paise as a 64-bit integer, per_user_limit (default 4), total_seats.
- show_seats: primary key (show_id, seat_label), state AVAILABLE or CONFIRMED, nullable reservation_id.
- show_user_state: primary key (show_id, user_id), active_seat_count. This row serializes all bookings and cancellations for one user on one show.
- reservations: ID, show_id, user_id, amount_paise as a 64-bit integer, state CONFIRMED or CANCELLED, creation/cancellation timestamps.
- reservation_seats: primary key (reservation_id, seat_label), referencing a seat in the same show.
- idempotency_requests: unique (user_id, idempotency_key), canonical request fingerprint, final HTTP outcome, decline reason or reservation_id, and replay_count.

Foreign keys and check constraints reject invalid states. No seat or reservation is deleted on cancellation. Historical idempotency keys remain consumed.

## API behavior

- POST /shows: admin JWT required. Accepts name, nonempty unique seat labels, integer price_paise, and optional per_user_limit. Returns the new show with all seats available.
- GET /shows/{id}: returns each seat and counts for available, held, confirmed, and total. This model has no timed hold, so held is always zero. The seat list and counts come from one consistent database snapshot.
- POST /shows/{id}/reserve: user JWT required. Accepts a nonempty list of unique seat labels and an idempotency key. Seat labels are canonicalized into sorted order for the request fingerprint. A new successful booking returns 201 and a confirmed reservation.
- POST /reservations/{id}/cancel: owner JWT required. Cancels an active reservation and releases every seat atomically. A repeated owner cancellation returns the already-cancelled state without releasing again. Another user cannot cancel it.
- POST /auth/guest: a documented demo endpoint issues a short-lived, signed JWT for a fresh server-generated user ID. It lets evaluators and the burst script obtain user tokens without managing accounts. Tokens are obtained before the measured burst.

An admin JWT is generated outside the public API and shared with the evaluator in submission instructions. No signing secret or admin token is committed to Git. A client-supplied user_id is ignored for authorization; every action uses the verified JWT subject.

New requests that lose a seat race or exceed the limit return 409 with a stable machine-readable reason. Invalid inputs return 400, missing shows or seats return 404, and missing or invalid JWTs return 401.

## Atomic reservation algorithm

All steps below occur in one PostgreSQL transaction:

1. Insert the (user_id, idempotency_key) record with the canonical request fingerprint. On a uniqueness conflict, wait for the original transaction, compare fingerprints, and return either 409 for different input or the recorded result for the same input.
2. Ensure a show_user_state row exists, then lock it. Check active_seat_count plus requested seat count against the show's limit.
3. Create a savepoint and insert an uncommitted reservation row with a generated ID. Visit requested seats in sorted label order. A conditional update assigns each seat to that reservation only if its state is AVAILABLE, then insert its reservation_seats link.
4. If any claim affects zero rows, roll back to the savepoint so the reservation and all earlier seat claims disappear. Query whether the seat is missing or taken, record the resulting 404 or 409 as the idempotent outcome, and commit that outcome.
5. If all seats were claimed, increase active_seat_count, record the successful idempotent outcome, and commit.

The savepoint is created after the idempotency and user-state rows are locked, so a decline retains those locks until its outcome is committed. No path can commit some requested seats while declining the request. Every path locks the user state before seat rows, and multi-seat rows in sorted order.

Distinct users racing for one seat cannot both change it from AVAILABLE. The loser receives 409 after the winner commits. Concurrent requests by one user serialize on show_user_state and cannot exceed the limit.

The first success returns 201. A same-key replay returns 200 with the same reservation ID and its current status, without a new booking. If that reservation was later cancelled, replay returns its CANCELLED status and cannot reclaim seats. Replaying a recorded decline returns the same 409 reason. Same key with different show or seat set returns 409.

## Cancellation and reconciliation

Cancellation first identifies the reservation, then locks show_user_state, its seat rows in sorted order, and the reservation using the same order as booking where those resources overlap. It changes CONFIRMED to CANCELLED, changes only those seats still owned by that reservation back to AVAILABLE, and decrements the active count in one transaction. Repeated cancellation cannot decrement twice or release a later owner's seat.

The reconciliation invariant is available + held + confirmed = total_seats. GET /shows/{id} reads a consistent snapshot so a concurrent commit cannot split the per-seat states and counts across different instants. Cancellation returns seats to AVAILABLE; historical reservations remain in the database.

## Operations and observability

- GET /health/live confirms the process is running without requiring PostgreSQL.
- GET /health/ready checks PostgreSQL connectivity and fails closed when it is unavailable.
- Prometheus output includes reservations_confirmed_total, reservations_declined_total with seat-taken, per-user-limit, and idempotent-replay reasons, idempotency_replays_total, and seats_available. Durable idempotency outcomes and replay counts back counters; the available gauge reads database state. The idempotent-replay decline label means no new booking was made, even though a successful replay's HTTP status is 200.
- JSON logs include request/correlation ID, route, outcome reason, latency, and reservation/show IDs where applicable. JWTs and secrets are excluded.
- The burst command creates a fresh show, pre-issues user tokens, runs a hot-seat storm, same-key retries, a concurrent per-user-limit scenario, and multi-seat contention; it prints HTTP and domain-outcome distribution, 5xx count, and final reconciliation.
- The README documents local startup, configuration, token acquisition, API examples, the burst command, deployment, metrics, and log access. WRITEUP.md explains the atomic mechanism, lock order, idempotency, cancellation, partition trade-off, alerting, honest AI usage, and follow-up work.

The service favors consistency over availability if PostgreSQL is unavailable: readiness fails and reservation writes stop rather than guessing seat state.

## Verification

Use PostgreSQL-backed integration tests for hot-seat exclusivity, multi-seat all-or-nothing behavior, parallel per-user limits, replay/conflict behavior, cancellation ownership, and reconciliation. Unit tests cover validation and request fingerprinting where useful. Verify a clean checkout builds with the Maven wrapper, then run the burst against the public URL and inspect metrics and logs. Test a cold start separately.

The current local machine has Java 17 and Maven but no Docker or PostgreSQL command available. Full database and container verification requires an available PostgreSQL and container runtime or a deployed test environment. A free host may not have enough capacity for the stated 20,000-request burst; the live test must establish the observed result rather than assume it.

## Open implementation choices

- Select a compatible, pinned Spring Boot version and dependency versions in the implementation plan.
- Choose a concrete way to run PostgreSQL-backed tests in this environment.
- Use kunalshah0492@gmail.com as this repository's Git author email before the first commit.
