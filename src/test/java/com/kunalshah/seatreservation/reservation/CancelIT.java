package com.kunalshah.seatreservation.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class CancelIT {
    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void ownerCanCancelOnceRebookAndReplayOldKeyWithoutReclaiming() throws Exception {
        String show = createShow(List.of("A1", "A2"));
        String owner = "owner-" + UUID.randomUUID();
        Reply booked = reserve(show, owner, "first", "[\"A1\",\"A2\"]");
        assertThat(booked.status()).isEqualTo(201);
        String id = JsonPath.read(booked.body(), "$.id");

        Reply cancelled = cancel(id, owner);
        Reply repeated = cancel(id, owner);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(repeated.status()).isEqualTo(200);
        assertThat(JsonPath.read(cancelled.body(), "$.status").toString()).isEqualTo("cancelled");
        assertThat(JsonPath.read(repeated.body(), "$.status").toString()).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject(
                "select active_seat_count from show_user_state where show_id = ? and user_id = ?",
                Integer.class, UUID.fromString(show), owner)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from show_seats where show_id = ? and state = 'AVAILABLE'",
                Integer.class, UUID.fromString(show))).isEqualTo(2);

        Reply newBooking = reserve(show, "second-owner", "new", "[\"A1\"]");
        assertThat(newBooking.status()).isEqualTo(201);
        Reply replay = reserve(show, owner, "first", "[\"A2\",\"A1\"]");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(JsonPath.read(replay.body(), "$.id").toString()).isEqualTo(id);
        assertThat(JsonPath.read(replay.body(), "$.status").toString()).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject(
                "select reservation_id from show_seats where show_id = ? and seat_label = 'A1'",
                UUID.class, UUID.fromString(show)))
                .isEqualTo(UUID.fromString(JsonPath.read(newBooking.body(), "$.id")));
    }

    @Test
    void nonOwnerCannotCancelAndSeatStaysOwned() throws Exception {
        String show = createShow(List.of("B1"));
        String owner = "owner-" + UUID.randomUUID();
        Reply booked = reserve(show, owner, "owned", "[\"B1\"]");
        assertThat(booked.status()).isEqualTo(201);
        String id = JsonPath.read(booked.body(), "$.id");

        Reply denied = cancel(id, "different-user");
        assertThat(denied.status()).isEqualTo(403);
        assertThat(JsonPath.read(denied.body(), "$.error").toString()).isEqualTo("not_owner");
        assertThat(jdbc.queryForObject(
                "select state from reservations where id = ?",
                String.class, UUID.fromString(id))).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "select state from show_seats where show_id = ? and seat_label = 'B1'",
                String.class, UUID.fromString(show))).isEqualTo("CONFIRMED");
    }

    @Test
    void cancellationRestoresFourSeatCapacity() throws Exception {
        String show = createShow(List.of("C1", "C2", "C3", "C4", "C5"));
        String owner = "owner-" + UUID.randomUUID();
        Reply first = reserve(show, owner, "four", "[\"C1\",\"C2\",\"C3\",\"C4\"]");
        assertThat(first.status()).isEqualTo(201);
        String id = JsonPath.read(first.body(), "$.id");
        assertThat(reserve(show, owner, "too-many", "[\"C5\"]").status()).isEqualTo(409);
        assertThat(cancel(id, owner).status()).isEqualTo(200);
        assertThat(reserve(show, owner, "after-cancel", "[\"C5\"]").status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "select active_seat_count from show_user_state where show_id = ? and user_id = ?",
                Integer.class, UUID.fromString(show), owner)).isEqualTo(1);
    }

    @Test
    void concurrentRepeatCancellationNeverDecrementsTwice() throws Exception {
        String show = createShow(List.of("D1"));
        String owner = "owner-" + UUID.randomUUID();
        Reply booked = reserve(show, owner, "initial", "[\"D1\"]");
        assertThat(booked.status()).isEqualTo(201);
        String id = JsonPath.read(booked.body(), "$.id");
        Reply[] replies = race(() -> cancel(id, owner), () -> cancel(id, owner));
        assertThat(replies[0].status()).isEqualTo(200);
        assertThat(replies[1].status()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "select active_seat_count from show_user_state where show_id = ? and user_id = ?",
                Integer.class, UUID.fromString(show), owner)).isZero();
        assertThat(jdbc.queryForObject(
                "select state from show_seats where show_id = ? and seat_label = 'D1'",
                String.class, UUID.fromString(show))).isEqualTo("AVAILABLE");
    }

    @Test
    void cancelRacingNewBookingNeverReleasesLaterOwnersSeat() throws Exception {
        for (int i = 0; i < 10; i++) {
            String show = createShow(List.of("E1"));
            String owner = "owner-" + UUID.randomUUID();
            Reply booked = reserve(show, owner, "initial", "[\"E1\"]");
            assertThat(booked.status()).isEqualTo(201);
            String id = JsonPath.read(booked.body(), "$.id");
            String contender = "contender-" + UUID.randomUUID();

            Reply[] race = race(() -> cancel(id, owner),
                    () -> reserve(show, contender, "race", "[\"E1\"]"));
            assertThat(race[0].status()).isEqualTo(200);
            assertThat(race[1].status()).isIn(201, 409);
            assertThat(jdbc.queryForObject(
                    "select state from reservations where id = ?",
                    String.class, UUID.fromString(id))).isEqualTo("CANCELLED");
            if (race[1].status() == 201) {
                assertThat(jdbc.queryForObject(
                        "select reservation_id from show_seats where show_id = ? and seat_label = 'E1'",
                        UUID.class, UUID.fromString(show)))
                        .isEqualTo(UUID.fromString(JsonPath.read(race[1].body(), "$.id")));
            } else {
                assertThat(jdbc.queryForObject(
                        "select state from show_seats where show_id = ? and seat_label = 'E1'",
                        String.class, UUID.fromString(show))).isEqualTo("AVAILABLE");
                assertThat(reserve(show, contender, "after-race", "[\"E1\"]").status())
                        .isEqualTo(201);
            }
        }
    }

    private String createShow(List<String> seats) throws Exception {
        String seatJson = seats.stream().map(seat -> "\"" + seat + "\"")
                .collect(Collectors.joining(","));
        String body = "{\"name\":\"cancel-" + UUID.randomUUID()
                + "\",\"seats\":[" + seatJson + "],\"price_paise\":100}";
        MvcResult result = mvc.perform(post("/shows")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private Reply reserve(String show, String user, String key, String seats) throws Exception {
        String body = "{\"seats\":" + seats + ",\"idempotency_key\":\"" + key + "\"}";
        MvcResult result = mvc.perform(post("/shows/{id}/reserve", show)
                .with(jwt().jwt(token -> token.subject(user))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        return new Reply(result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    private Reply cancel(String id, String user) throws Exception {
        MvcResult result = mvc.perform(post("/reservations/{id}/cancel", id)
                .with(jwt().jwt(token -> token.subject(user))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andReturn();
        return new Reply(result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    private Reply[] race(ThrowingRequest left, ThrowingRequest right) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Reply> first = pool.submit(() -> {
                start.await();
                return left.execute();
            });
            Future<Reply> second = pool.submit(() -> {
                start.await();
                return right.execute();
            });
            start.countDown();
            return new Reply[] {
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)
            };
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingRequest {
        Reply execute() throws Exception;
    }

    private record Reply(int status, String body) {
    }
}
