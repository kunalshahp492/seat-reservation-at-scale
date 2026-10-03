# Seat Reservation at Scale

A Spring Boot 4 / Java 17 JSON API backed by PostgreSQL 17. The database makes the final seat decision. A reservation of multiple seats either confirms every requested seat or confirms none.

## Run locally

Requirements: Docker with Compose, or Java 17 plus PostgreSQL 17 for a direct run.

In PowerShell, generate a local signing secret and start the stack:

```powershell
$env:JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
docker compose up --build -d
Invoke-RestMethod http://localhost:8080/health/ready
```

The secret must contain at least 32 UTF-8 bytes. Keep the same value while the service is running; changing it invalidates existing JWTs. PostgreSQL data remains in the `postgres_data` volume. `docker compose down` stops the stack.

For a direct run, set `DB_URL` (JDBC URL), `DB_USER`, `DB_PASSWORD`, and `JWT_SECRET`, then run `./mvnw spring-boot:run` on Unix or `./mvnw.cmd spring-boot:run` on Windows. `PORT` defaults to `8080`. Flyway applies the schema on startup. PostgreSQL must already be available.

## Tokens and API

An ADMIN token creates shows; a guest token can reserve and cancel its own reservations. To mint an ADMIN token, set `JWT_SECRET` in the current shell to the same value used by the server:

```powershell
$admin = python scripts/mint_admin_jwt.py
$guest = Invoke-RestMethod -Method Post http://localhost:8080/auth/guest
$guest.token
```

Guest tokens are signed by the service, have a server-generated user ID, and expire after one hour. Send the token as `Authorization: Bearer <token>`. The user ID comes only from the verified token; a request body cannot choose a user.

Create a show:

```powershell
$show = Invoke-RestMethod -Method Post http://localhost:8080/shows `
  -Headers @{Authorization="Bearer $admin"} -ContentType 'application/json' `
  -Body '{"name":"Friday concert","seats":["A1","A2","A3","A4","A5"],"price_paise":25000}'
$show.id
```

Read its current seat states with `GET /shows/{id}`. Reserve seats with a guest token and a unique `idempotency_key`:

```powershell
$reservation = Invoke-RestMethod -Method Post "http://localhost:8080/shows/$($show.id)/reserve" `
  -Headers @{Authorization="Bearer $($guest.token)"} -ContentType 'application/json' `
  -Body '{"seats":["A1","A2"],"idempotency_key":"example-booking-1"}'
$reservation.amount_paise
```

The default limit is four **active** seats per user per show across all transactions. Show creation can set `per_user_limit` to another positive value. A new booking returns `201`; a successful same-key replay returns `200` with the original reservation. A changed request with the same key returns `409 idempotency_conflict`. A taken seat or an exceeded user limit returns `409` with `seat_taken` or `per_user_limit`. A missing seat returns `404 seat_not_found`. Reordered seats with the same key represent the same request. Prices and reservation amounts are integer paise.

The owner can cancel with `POST /reservations/{id}/cancel` using the same guest token. A repeated cancellation returns the cancelled reservation. A successful booking's idempotency key remains bound to that reservation even after cancellation.

## Concurrency burst

The client uses only the Java 17 JDK. It creates four isolated shows, obtains guest tokens and seeds replay/limit scenarios before the timer, then sends a bounded asynchronous burst. It checks the response distribution and final seat counts. The default is 20,000 measured reserve requests:

```powershell
java scripts/Burst.java http://localhost:8080 $admin 20000
```

The output reports `new_confirmed`, `replay`, `seat_taken`, `per_user_limit`, `other_4xx`, `5xx`, `transport_errors`, per-show counts, and `reconciliation=PASS`. The client exits nonzero on any mismatch. CI runs a 24-request smoke burst against PostgreSQL. The `--smoke` suffix is accepted for that command.

## Health, metrics, and logs

- `GET /health/live` checks only that the process responds.
- `GET /health/ready` checks PostgreSQL and returns `503` when it is unavailable.
- `GET /actuator/prometheus` exposes `reservations_confirmed_total`, `reservations_declined_total{reason}`, `idempotency_replays_total`, and `seats_available`.

The reservation counters query durable idempotency outcomes and replay counts; `seats_available` queries current seat rows. `reason="idempotent_replay"` counts repeated requests that created no new booking, including successful replays. The metrics endpoint is publicly readable for evaluation. Request logs are ECS JSON with request ID, route, status, outcome reason, latency, and show/reservation IDs when known. The `X-Request-Id` header is echoed; invalid IDs are replaced. JWTs and request bodies are not logged. For local logs, run `docker compose logs -f api`.

## Tests

`./mvnw verify` runs unit tests. `./mvnw verify -Ppostgres-it` also runs the PostgreSQL integration tests, including a 500-user hot-seat race. GitHub Actions starts PostgreSQL 17, runs this profile, runs the burst smoke, and builds the Docker image. The local development machine used for this assignment has no Docker or PostgreSQL command installed, so those checks run in CI.

## Deployment

The live API is at **https://seat-reservation-at-scale-h295.onrender.com**. Its [liveness](https://seat-reservation-at-scale-h295.onrender.com/health/live), [readiness](https://seat-reservation-at-scale-h295.onrender.com/health/ready), and [Prometheus metrics](https://seat-reservation-at-scale-h295.onrender.com/actuator/prometheus) endpoints are public. The [Render application logs](https://dashboard.render.com/web/srv-davrrirtqb8s73dio8i0/logs) require access to the Render workspace; [this short recording](docs/evidence/render-live-logs.mp4) shows the live structured logs under load.

The Dockerfile accepts `PORT` (provided by the host) and requires `DB_URL`, `DB_USER`, `DB_PASSWORD`, and `JWT_SECRET`. The Render web service uses the repository Dockerfile, a Render PostgreSQL 17 database in the same region, and `/health/ready` as its HTTP health check. The free web instance can take 50 seconds or more to wake after idle time. Its free PostgreSQL instance expires on November 1, 2026 unless upgraded.

Run the same one-command burst against the live service with a valid ADMIN JWT:

```powershell
java scripts/Burst.java https://seat-reservation-at-scale-h295.onrender.com $admin 20000
```

Provide the ADMIN JWT privately to reviewers when submitting; never commit it. Anyone can obtain guest JWTs from `POST /auth/guest`; ADMIN is needed to create fresh shows for testing. See `WRITEUP.md` for measured live results and limitations.
