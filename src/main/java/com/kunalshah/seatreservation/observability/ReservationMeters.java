package com.kunalshah.seatreservation.observability;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReservationMeters implements MeterBinder {
    private final JdbcTemplate jdbc;

    public ReservationMeters(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder("reservations.confirmed", jdbc,
                db -> count("select count(*) from idempotency_requests where http_status = 201"))
                .description("Durable successful reservation decisions")
                .register(registry);
        for (String reason : new String[] {"seat_taken", "seat_not_found", "per_user_limit"}) {
            FunctionCounter.builder("reservations.declined", jdbc,
                    db -> count("select count(*) from idempotency_requests where decline_reason = ?", reason))
                    .tag("reason", reason)
                    .description("Durable declined reservation decisions")
                    .register(registry);
        }
        FunctionCounter.builder("reservations.declined", jdbc,
                db -> count("select coalesce(sum(replay_count), 0) from idempotency_requests"))
                .tag("reason", "idempotent_replay")
                .description("Repeated requests that produced no new booking")
                .register(registry);
        FunctionCounter.builder("idempotency.replays", jdbc,
                db -> count("select coalesce(sum(replay_count), 0) from idempotency_requests"))
                .description("Durable idempotency replay count")
                .register(registry);
        Gauge.builder("seats.available", jdbc,
                db -> count("select count(*) from show_seats where state = 'AVAILABLE'"))
                .description("Currently available seats")
                .register(registry);
    }

    private double count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
