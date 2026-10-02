package com.kunalshah.seatreservation.show;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {
    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
        jdbc.update(
                "insert into shows (id, name, price_paise, per_user_limit, total_seats) "
                        + "values (?, ?, ?, ?, ?)",
                id, name, pricePaise, perUserLimit, totalSeats);
    }

    public void insertSeat(UUID showId, String seatLabel) {
        jdbc.update("insert into show_seats (show_id, seat_label) values (?, ?)",
                showId, seatLabel);
    }

    public List<ShowSeatRow> findRows(UUID showId) {
        return jdbc.query(
                "select s.id, s.name, s.price_paise, s.per_user_limit, s.total_seats, "
                        + "ss.seat_label, ss.state "
                        + "from shows s join show_seats ss on ss.show_id = s.id "
                        + "where s.id = ? order by ss.seat_label",
                (rs, rowNum) -> new ShowSeatRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getLong("price_paise"),
                        rs.getInt("per_user_limit"),
                        rs.getInt("total_seats"),
                        rs.getString("seat_label"),
                        rs.getString("state")),
                showId);
    }

    public record ShowSeatRow(
            UUID id,
            String name,
            long pricePaise,
            int perUserLimit,
            int totalSeats,
            String seatLabel,
            String state) {
    }
}
