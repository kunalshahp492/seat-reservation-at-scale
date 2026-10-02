package com.kunalshah.seatreservation.reservation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {
    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ShowPolicy showPolicy(UUID showId) {
        List<ShowPolicy> rows = jdbc.query(
                "select price_paise, per_user_limit from shows where id = ?",
                (rs, rowNum) -> new ShowPolicy(
                        rs.getLong("price_paise"), rs.getInt("per_user_limit")),
                showId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int lockUserState(UUID showId, String userId) {
        jdbc.update(
                "insert into show_user_state (show_id, user_id) values (?, ?) "
                        + "on conflict (show_id, user_id) do nothing",
                showId, userId);
        return jdbc.queryForObject(
                "select active_seat_count from show_user_state "
                        + "where show_id = ? and user_id = ? for update",
                Integer.class, showId, userId);
    }

    public void insertReservation(UUID id, UUID showId, String userId, long amountPaise) {
        jdbc.update(
                "insert into reservations (id, show_id, user_id, amount_paise, state) "
                        + "values (?, ?, ?, ?, 'CONFIRMED')",
                id, showId, userId, amountPaise);
    }

    public int claimSeat(UUID showId, String label, UUID reservationId) {
        return jdbc.update(
                "update show_seats set state = 'CONFIRMED', reservation_id = ? "
                        + "where show_id = ? and seat_label = ? and state = 'AVAILABLE'",
                reservationId, showId, label);
    }

    public void attachSeat(UUID reservationId, UUID showId, String label) {
        jdbc.update(
                "insert into reservation_seats (reservation_id, show_id, seat_label) "
                        + "values (?, ?, ?)",
                reservationId, showId, label);
    }

    public boolean seatExists(UUID showId, String label) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from show_seats where show_id = ? and seat_label = ?)",
                Boolean.class, showId, label));
    }

    public void addActiveSeats(UUID showId, String userId, int count) {
        jdbc.update(
                "update show_user_state set active_seat_count = active_seat_count + ? "
                        + "where show_id = ? and user_id = ?",
                count, showId, userId);
    }

    public List<ReservationRow> findReservation(UUID reservationId) {
        return jdbc.query(
                "select r.id, r.show_id, r.user_id, r.amount_paise, r.state, "
                        + "r.created_at, r.cancelled_at, rs.seat_label "
                        + "from reservations r join reservation_seats rs on rs.reservation_id = r.id "
                        + "where r.id = ? order by rs.seat_label",
                (rs, rowNum) -> {
                    Timestamp cancelled = rs.getTimestamp("cancelled_at");
                    return new ReservationRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("show_id", UUID.class),
                            rs.getString("user_id"),
                            rs.getLong("amount_paise"),
                            rs.getString("state"),
                            rs.getTimestamp("created_at").toInstant(),
                            cancelled == null ? null : cancelled.toInstant(),
                            rs.getString("seat_label"));
                },
                reservationId);
    }

    public record ShowPolicy(long pricePaise, int perUserLimit) {
    }

    public record ReservationRow(
            UUID id,
            UUID showId,
            String userId,
            long amountPaise,
            String state,
            Instant createdAt,
            Instant cancelledAt,
            String seatLabel) {
    }
}
