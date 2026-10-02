# Seat Reservation at Scale Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Deliver a deployed Spring Boot seat-reservation API that remains correct under concurrent bookings and makes its behavior observable.

**Architecture:** PostgreSQL owns seat state, user counts, and idempotency outcomes. A Spring Boot service makes reserve and cancel decisions in ordered database transactions; JWT identifies the caller. Docker packages the same application for local use and Render.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Maven wrapper, Spring Security JWT, Spring JDBC, PostgreSQL 17, Flyway, Actuator, Micrometer Prometheus, GitHub Actions.

**Spec:** docs/superpowers/specs/2026-10-02-seat-reservation-design.md

## Global Constraints

- Money uses integer amount_paise and price_paise; never floating point.
- JSON preserves the assignment's snake_case field names and lowercase seat/reservation status values.
- The default per_user_limit is 4 active seats per user per show.
- A multi-seat reserve is all-or-nothing; seat rows are visited in sorted label order.
- New success is 201, same-key success replay is 200, and domain declines are 409.
- JWT subject supplies user identity. Guest tokens expire after one hour; JWT_SECRET must contain at least 32 random bytes. No signing secret or admin token enters Git.
- Explicit owner cancellation releases seats. This version has no timed holds; held count is zero.
- The repository stays private until the user asks to make it public; it must be public before assignment submission.
- Java 17 and Maven are available locally. Docker and PostgreSQL are not; GitHub Actions with a PostgreSQL service is the authoritative integration-test environment until another database is available.
- For a PostgreSQL-backed red/green cycle, commit and push the test-only change to the private repository, inspect its failing CI run, implement the feature, then push and inspect the green run. Local unit tests still run before each push.

## Review Focus

These input classes need explicit tests in their owning tasks:

1. Duplicate or empty seat labels in show creation or reserve input return 400 with no state change (Tasks 3–4).
2. Missing or blank idempotency keys return 400 before any seat or count changes (Task 4).
3. Reordered seats with the same key replay; a different show or seat set with that key returns 409 (Task 4).
4. A cancellation repeated or racing with another booking never decrements twice or releases another reservation's seat (Task 5).
5. Expired, wrong-issuer, and wrong-role JWTs cannot gain access; a spoofed body user_id cannot change the owner (Tasks 2 and 4).

## File and interface map

- Build/DB: pom.xml, Maven wrapper files, src/main/resources/application.yml, src/main/resources/db/migration/V1__init.sql, .github/workflows/ci.yml.
- App/API: src/main/java/com/kunalshah/seatreservation/SeatReservationApplication.java and api/ApiExceptionHandler.java.
- Auth: security/SecurityConfig.java, security/JwtService.java, security/AuthController.java, scripts/mint_admin_jwt.py.
- Shows: show/ShowController.java, show/ShowService.java, show/ShowRepository.java, show/ShowDtos.java.
- Reservations: reservation/ReservationController.java, reservation/ReservationService.java, reservation/ReservationRepository.java, reservation/IdempotencyRepository.java, reservation/ReservationDtos.java.
- Operations: observability/RequestIdFilter.java, observability/ReservationMeters.java, Dockerfile, compose.yaml, scripts/Burst.java, README.md, WRITEUP.md.
- Tests mirror those packages under src/test/java; PostgreSQL integration tests end in IT and run in Maven's postgres-it profile.

### Task 1: Build, schema, and CI database test harness

**Files:** Create pom.xml, wrapper files, application.yml, V1__init.sql, SeatReservationApplication.java, .gitignore, .github/workflows/ci.yml, src/test/java/com/kunalshah/seatreservation/db/SchemaIT.java.

**Interfaces:** Produces the six tables and constraints named in the spec; TEST_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD configure integration tests. Maven's postgres-it profile runs *IT with Failsafe. CI uses PostgreSQL 17, actions/checkout@v7, actions/setup-java@v6, and Java 17.

- [ ] Step 1: Add the minimal Spring Boot 4.1.1 Maven build, wrapper, application class/configuration, and CI PostgreSQL job; leave the schema migration absent.
- [ ] Step 2: Write SchemaIT asserting migration creates all six tables, default show limit 4, unique (show_id, seat_label), and unique (user_id, idempotency_key).
- [ ] Step 3: Commit and push the bootstrap plus failing SchemaIT to CI's PostgreSQL service; confirm its red job fails because the schema is absent.
- [ ] Step 4: Add the Flyway migration.
- [ ] Step 5: Run local Maven unit build and CI postgres-it verify; require a green migration test and no dependency-resolution errors.
- [ ] Step 6: Commit the migration and green test.

### Task 2: JWT issuance and request authorization

**Files:** Create security/SecurityConfig.java, security/JwtService.java, security/AuthController.java, scripts/mint_admin_jwt.py, api/ApiExceptionHandler.java, security/JwtSecurityTest.java.

**Interfaces:** JwtService.issueGuest() returns a TokenResponse(token, userId, expiresAt); JwtService.subject(Jwt) returns String. Security rules allow POST /auth/guest, GET /shows/{id}, health, and Prometheus metrics unauthenticated, require ADMIN for POST /shows, and require USER for reserve/cancel. The admin script signs HS256 JWTs using JWT_SECRET with issuer, audience, expiry, subject, and role claims.

- [ ] Step 1: Write tests asserting guest tokens have a random subject, one-hour expiry, and USER role; expired or wrong-issuer tokens get 401; USER cannot create shows; ADMIN cannot use USER-only routes.
- [ ] Step 2: Run the security tests and confirm they fail on missing auth configuration.
- [ ] Step 3: Implement HS256 signing/verification, issuer/audience/expiry validation, role mapping, demo guest endpoint, admin-token script, and stable JSON error responses.
- [ ] Step 4: Run security tests; require every stated 401/403 and token-claim assertion to pass.
- [ ] Step 5: Commit JWT and API error handling.

### Task 3: Show creation and consistent show state

**Files:** Create show/ShowDtos.java, show/ShowController.java, show/ShowService.java, show/ShowRepository.java, show/ShowIT.java.

**Interfaces:** ShowService.create(ShowCreateRequest) returns ShowView; ShowService.get(UUID showId) returns ShowView. ShowCreateRequest has name, seats, pricePaise, optional perUserLimit. ShowView exposes show ID, seat states, counts, pricePaise, and perUserLimit.

- [ ] Step 1: Write ShowIT for POST /shows returning every seat AVAILABLE, limit default 4, custom limit, and integer price; GET counts reconcile. Empty or duplicate labels and negative price return 400 without a show.
- [ ] Step 2: Commit and push ShowIT; confirm CI postgres-it verify fails on missing endpoints.
- [ ] Step 3: Implement show validation/inserts and one-snapshot read of seat states and counts; held stays zero.
- [ ] Step 4: Run postgres-it verify; require ShowIT and SchemaIT green.
- [ ] Step 5: Commit show API and state.

### Task 4: Atomic reserve, user limit, and idempotency

**Files:** Create reservation/ReservationDtos.java, reservation/ReservationController.java, reservation/ReservationService.java, reservation/ReservationRepository.java, reservation/IdempotencyRepository.java, reservation/ReserveIT.java.

**Interfaces:** ReservationService.reserve(UUID showId, String userId, ReserveRequest request) returns ReservationResult(httpStatus, ReservationView). ReserveRequest has seats and idempotencyKey. Repository operations claim the idempotency row, lock or create show_user_state, insert the reservation, conditionally update seats, and record the outcome.

- [ ] Step 1: Write ReserveIT asserting 500 distinct users racing for A12 create one reservation and no duplicate ownership; two-seat races are all-or-nothing; ten parallel requests by one user leave at most four active seats.
- [ ] Step 2: Add ReserveIT for same-key replay (200, same ID), reordered seats replay, different seats/show on same key (409), blank key (400), duplicate labels (400), recorded decline replay (same 409), spoofed body user_id (token remains owner), checked integer amount multiplication, and GET reconciliation during the race.
- [ ] Step 3: Commit and push ReserveIT; confirm CI postgres-it verify fails for missing reserve behavior.
- [ ] Step 4: Implement the exact spec transaction: idempotency claim, user-state lock, savepoint, reservation insert, sorted conditional seat claims, rollback-to-savepoint on decline, count update, recorded outcome, commit. Map expected conflicts to 409; distinguish a missing seat as 404.
- [ ] Step 5: Run postgres-it verify repeatedly; require no 5xx, no duplicate seat owner, correct user counts, and all specified replay results.
- [ ] Step 6: Commit the reservation transaction and tests.

### Task 5: Owner cancellation and release safety

**Files:** Modify reservation/ReservationService.java, reservation/ReservationRepository.java, reservation/ReservationController.java; create reservation/CancelIT.java.

**Interfaces:** ReservationService.cancel(UUID reservationId, String userId) returns ReservationView with CANCELLED status. It locks user state before sorted seat rows, releases rows only where reservation_id still matches, and decrements active count once.

- [ ] Step 1: Write CancelIT for owner cancellation, non-owner denial, repeat cancellation, rebooking released seats, freed per-user capacity, a same-key replay returning the cancelled reservation, and cancel racing a new booking without resurrecting another reservation's seat.
- [ ] Step 2: Commit and push CancelIT; confirm CI postgres-it verify fails for missing cancellation behavior.
- [ ] Step 3: Implement owner check and one-transaction cancellation with idempotent repeat behavior.
- [ ] Step 4: Run postgres-it verify; require all cancellation and reserve tests green.
- [ ] Step 5: Commit cancellation and release tests.

### Task 6: Health, metrics, and structured request logs

**Files:** Create observability/RequestIdFilter.java, observability/DbReadinessIndicator.java, observability/ReservationMeters.java, observability/OperationsIT.java, observability/DbReadinessIndicatorTest.java; modify application.yml and api/ApiExceptionHandler.java.

**Interfaces:** GET /health/live excludes DB; GET /health/ready requires DB. GET /actuator/prometheus exposes reservations_confirmed_total, reservations_declined_total{reason}, idempotency_replays_total, and seats_available. Counters derive from durable idempotency outcomes/replay_count; gauge queries seat rows.

- [ ] Step 1: Write DbReadinessIndicatorTest with a DataSource that throws SQLException and assert DOWN; write OperationsIT asserting healthy readiness/liveness, an accessible Prometheus scrape after booking/decline/replay/cancel, and JSON logs with request ID but no JWT.
- [ ] Step 2: Run DbReadinessIndicatorTest locally and commit/push OperationsIT; confirm the missing readiness, metrics, and log behavior fails in CI.
- [ ] Step 3: Configure Actuator/Micrometer health groups, the DB-checking readiness indicator, DB-backed meters, and request-ID JSON logging.
- [ ] Step 4: Run postgres-it verify and inspect a Prometheus scrape; require metric counts to reconcile with API state.
- [ ] Step 5: Commit operations endpoints and logs.

### Task 7: Container, burst client, and local run instructions

**Files:** Create Dockerfile, compose.yaml, scripts/Burst.java, README.md; modify .github/workflows/ci.yml to build the image.

**Interfaces:** java scripts/Burst.java BASE_URL ADMIN_JWT [REQUESTS] uses only JDK 17; REQUESTS defaults to 20000. It emits new-confirmed, replay, seat-taken, per-user-limit, other-4xx, 5xx, and final seat counts. It gets guest JWTs before timing the burst.

- [ ] Step 1: Add and push a burst smoke check against a CI-started API: one hot seat produces one new 201; output reports 0 5xx and reconciliation. Confirm it fails before Burst.java exists.
- [ ] Step 2: Implement multi-stage Dockerfile, Compose API+PostgreSQL, and asynchronous Java HttpClient burst scenarios for hot-seat, replay, user-limit, and multi-seat contention.
- [ ] Step 3: Run a clean Maven build, CI Docker build, and burst smoke check; require the printed distribution and final counts to match the API.
- [ ] Step 4: Document local startup, JWT setup, endpoints, metric/log access, and one-command burst invocation in README.
- [ ] Step 5: Commit container, burst tool, and run instructions.

### Task 8: Public deployment, live verification, and submission write-up

**Files:** Create WRITEUP.md; update README.md with actual deployment URL, metrics/log access, and burst evidence.

**Interfaces:** Render runs the repo Dockerfile with an external PostgreSQL database and environment secrets. Readiness is the service health path. No payment gateway is integrated.

- [ ] Step 1: Deploy the current commit to Render, configure PostgreSQL and JWT secrets, and verify a cold start reaches live and ready health endpoints.
- [ ] Step 2: Run the one-command burst against the public URL, inspect JSON logs and Prometheus metrics, and fix any observed 5xx, reconciliation mismatch, or throughput bottleneck before claiming success.
- [ ] Step 3: Write WRITEUP.md with exact SQL atomicity/lock order, idempotency storage, cancellation, partition trade-off, 2am alerts, honest AI usage, and next improvements. Provide public log access if Render supports it; otherwise capture a short recording of live logs under load.
- [ ] Step 4: From a clean clone, run Maven verify and the container build in CI; confirm all required deliverables are present and the live URL responds.
- [ ] Step 5: Commit observed results and documentation. Change GitHub visibility to public only when the user authorizes that change for submission.

## Execution notes

Task 4 is the highest-risk review point; inspect the actual SQL and transactional rollback behavior before proceeding. The CI PostgreSQL tests provide race evidence, while the live burst establishes whether the selected hosting tier meets the zero-5xx requirement. Do not claim the 20,000-request bar until the deployed run shows it.
