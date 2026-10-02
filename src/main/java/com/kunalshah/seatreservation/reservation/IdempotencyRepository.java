package com.kunalshah.seatreservation.reservation;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {
    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claim(
            UUID id, String userId, String key, UUID showId, String fingerprint) {
        return jdbc.update(
                "insert into idempotency_requests "
                        + "(id, user_id, idempotency_key, show_id, request_fingerprint) "
                        + "values (?, ?, ?, ?, ?) "
                        + "on conflict (user_id, idempotency_key) do nothing",
                id, userId, key, showId, fingerprint) == 1;
    }

    public Outcome find(String userId, String key) {
        List<Outcome> rows = jdbc.query(
                "select request_fingerprint, http_status, decline_reason, reservation_id "
                        + "from idempotency_requests where user_id = ? and idempotency_key = ?",
                (rs, rowNum) -> new Outcome(
                        rs.getString("request_fingerprint"),
                        rs.getObject("http_status", Integer.class),
                        rs.getString("decline_reason"),
                        rs.getObject("reservation_id", UUID.class)),
                userId, key);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Idempotency conflict has no committed outcome");
        }
        return rows.get(0);
    }

    public void completeSuccess(UUID id, UUID reservationId) {
        jdbc.update(
                "update idempotency_requests set http_status = 201, reservation_id = ?, "
                        + "completed_at = now() where id = ?",
                reservationId, id);
    }

    public void completeDecline(UUID id, int status, String reason) {
        jdbc.update(
                "update idempotency_requests set http_status = ?, decline_reason = ?, "
                        + "completed_at = now() where id = ?",
                status, reason, id);
    }

    public void incrementReplay(String userId, String key) {
        jdbc.update(
                "update idempotency_requests set replay_count = replay_count + 1 "
                        + "where user_id = ? and idempotency_key = ?",
                userId, key);
    }

    public record Outcome(
            String fingerprint,
            Integer httpStatus,
            String declineReason,
            UUID reservationId) {
    }
}
