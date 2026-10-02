package com.kunalshah.seatreservation.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
class ReserveIT {
    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void fiveHundredUsersRacingForOneSeatProduceOneOwnerAndConsistentReads() throws Exception {
        String show = createShow(List.of("A12"), 100);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                String user = "hot-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return reserve(show, user, "hot-key", "[\"A12\"]");
                }));
            }
            start.countDown();
            for (int i = 0; i < 30; i++) {
                assertReconciled(show, 1);
            }
            List<Reply> replies = collect(futures);
            assertThat(replies.stream().filter(reply -> reply.status() == 201).count()).isEqualTo(1);
            assertThat(replies.stream().filter(reply -> reply.status() == 409).count()).isEqualTo(499);
            assertThat(replies.stream().filter(reply -> reply.status() >= 500).count()).isZero();
            assertThat(jdbc.queryForObject(
                    "select count(*) from reservations where show_id = ? and state = 'CONFIRMED'",
                    Integer.class, UUID.fromString(show))).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select count(*) from show_seats where show_id = ? and reservation_id is not null",
                    Integer.class, UUID.fromString(show))).isEqualTo(1);
            assertReconciled(show, 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void overlappingTwoSeatRequestsAreAllOrNothing() throws Exception {
        String show = createShow(List.of("A1", "A2", "A3"), 100);
        List<Reply> replies = race(2, index -> reserve(show, "overlap-" + index,
                "key-" + index, index == 0 ? "[\"A1\",\"A2\"]" : "[\"A2\",\"A3\"]"));
        assertThat(replies.stream().filter(reply -> reply.status() == 201).count()).isEqualTo(1);
        assertThat(replies.stream().filter(reply -> reply.status() == 409).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from reservation_seats where show_id = ?",
                Integer.class, UUID.fromString(show))).isEqualTo(2);
        assertReconciled(show, 3);
    }

    @Test
    void tenParallelBookingsByOneUserNeverExceedFourActiveSeats() throws Exception {
        List<String> seats = IntStream.rangeClosed(1, 10).mapToObj(i -> "B" + i).toList();
        String show = createShow(seats, 100);
        List<Reply> replies = race(10, index -> reserve(show, "same-user",
                "limit-" + index, "[\"B" + (index + 1) + "\"]"));
        assertThat(replies.stream().filter(reply -> reply.status() == 201).count()).isEqualTo(4);
        assertThat(replies.stream().filter(reply -> reply.status() == 409).count()).isEqualTo(6);
        assertThat(replies.stream().filter(reply -> reply.status() >= 500).count()).isZero();
        assertThat(jdbc.queryForObject(
                "select active_seat_count from show_user_state where show_id = ? and user_id = ?",
                Integer.class, UUID.fromString(show), "same-user")).isEqualTo(4);
        assertReconciled(show, 10);
    }

    @Test
    void sameKeyReplaysCanonicalSeatSetButCannotChangeRequest() throws Exception {
        String show = createShow(List.of("C1", "C2", "C3"), 125);
        String otherShow = createShow(List.of("C1", "C2"), 125);
        Reply first = reserve(show, "replay-user", "same-key", "[\"C2\",\"C1\"]");
        Reply replay = reserve(show, "replay-user", "same-key", "[\"C1\",\"C2\"]");
        Reply differentSeat = reserve(show, "replay-user", "same-key", "[\"C3\"]");
        Reply differentShow = reserve(otherShow, "replay-user", "same-key", "[\"C1\",\"C2\"]");

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(JsonPath.read(replay.body(), "$.id").toString())
                .isEqualTo(JsonPath.read(first.body(), "$.id"));
        assertThat(JsonPath.read(first.body(), "$.amount_paise").toString()).isEqualTo("250");
        assertThat(differentSeat.status()).isEqualTo(409);
        assertThat(JsonPath.read(differentSeat.body(), "$.error").toString())
                .isEqualTo("idempotency_conflict");
        assertThat(differentShow.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "select replay_count from idempotency_requests where user_id = ? and idempotency_key = ?",
                Long.class, "replay-user", "same-key")).isEqualTo(1L);
    }

    @Test
    void invalidKeysAndSeatListsChangeNoRows() throws Exception {
        String show = createShow(List.of("D1", "D2"), 100);
        String user = "invalid-user";
        List<Reply> replies = List.of(
                reserve(show, user, "", "[\"D1\"]"),
                reserve(show, user, "   ", "[\"D1\"]"),
                reserveRaw(show, user, "{\"seats\":[\"D1\"]}"),
                reserve(show, user, "dup", "[\"D1\",\"D1\"]"),
                reserve(show, user, "empty", "[]"));
        assertThat(replies).allSatisfy(reply -> assertThat(reply.status()).isEqualTo(400));
        assertThat(jdbc.queryForObject(
                "select count(*) from idempotency_requests where user_id = ?",
                Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from reservations where show_id = ?",
                Integer.class, UUID.fromString(show))).isZero();
        assertReconciled(show, 2);
    }

    @Test
    void declinesReplayWithoutTakingSeatsAndMissingSeatReturns404() throws Exception {
        String show = createShow(List.of("E1"), 100);
        assertThat(reserve(show, "winner", "win", "[\"E1\"]").status()).isEqualTo(201);
        Reply decline = reserve(show, "loser", "loss", "[\"E1\"]");
        Reply replay = reserve(show, "loser", "loss", "[\"E1\"]");
        Reply missing = reserve(show, "loser", "missing", "[\"E2\"]");
        assertThat(decline.status()).isEqualTo(409);
        assertThat(replay.status()).isEqualTo(409);
        assertThat(JsonPath.read(decline.body(), "$.error").toString()).isEqualTo("seat_taken");
        assertThat(JsonPath.read(replay.body(), "$.error").toString()).isEqualTo("seat_taken");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(JsonPath.read(missing.body(), "$.error").toString()).isEqualTo("seat_not_found");
        assertThat(jdbc.queryForObject(
                "select replay_count from idempotency_requests where user_id = ? and idempotency_key = ?",
                Long.class, "loser", "loss")).isEqualTo(1L);
        assertReconciled(show, 1);
    }

    @Test
    void tokenSubjectOwnsReservationAndAmountUsesCheckedIntegers() throws Exception {
        String show = createShow(List.of("F1", "F2"), 3_000_000_000L);
        Reply reply = reserve(show, "real-user", "spoof", "[\"F1\",\"F2\"]",
                "\"user_id\":\"spoofed-user\"");
        assertThat(reply.status()).isEqualTo(201);
        assertThat(JsonPath.read(reply.body(), "$.amount_paise").toString())
                .isEqualTo("6000000000");
        assertThat(jdbc.queryForObject(
                "select user_id from reservations where id = ?",
                String.class, UUID.fromString(JsonPath.read(reply.body(), "$.id"))))
                .isEqualTo("real-user");

        String overflowShow = createShow(List.of("G1", "G2"), Long.MAX_VALUE);
        Reply overflow = reserve(overflowShow, "real-user", "overflow", "[\"G1\",\"G2\"]");
        assertThat(overflow.status()).isEqualTo(400);
        assertReconciled(overflowShow, 2);
    }

    @Test
    void limitAppliesAcrossSeparateTransactionsForSameShow() throws Exception {
        String show = createShow(List.of("H1", "H2", "H3", "H4", "H5"), 100);
        assertThat(reserve(show, "returning-user", "first", "[\"H1\",\"H2\",\"H3\",\"H4\"]")
                .status()).isEqualTo(201);
        Reply next = reserve(show, "returning-user", "second", "[\"H5\"]");
        assertThat(next.status()).isEqualTo(409);
        assertThat(JsonPath.read(next.body(), "$.error").toString()).isEqualTo("per_user_limit");
        assertReconciled(show, 5);
    }

    private String createShow(List<String> seats, long pricePaise) throws Exception {
        String seatJson = seats.stream().map(seat -> "\"" + seat + "\"")
                .collect(Collectors.joining(","));
        String body = "{\"name\":\"show-" + UUID.randomUUID()
                + "\",\"seats\":[" + seatJson + "],\"price_paise\":" + pricePaise + "}";
        MvcResult result = mvc.perform(post("/shows")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private Reply reserve(String show, String user, String key, String seats) throws Exception {
        return reserve(show, user, key, seats, null);
    }

    private Reply reserve(String show, String user, String key, String seats, String extraJson)
            throws Exception {
        String body = "{\"seats\":" + seats + ",\"idempotency_key\":\""
                + key + "\"" + (extraJson == null ? "" : "," + extraJson) + "}";
        return reserveRaw(show, user, body);
    }

    private Reply reserveRaw(String show, String user, String body) throws Exception {
        MvcResult result = mvc.perform(post("/shows/{id}/reserve", show)
                .with(jwt().jwt(token -> token.subject(user))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        return new Reply(result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    private void assertReconciled(String show, int total) throws Exception {
        MvcResult result = mvc.perform(get("/shows/{id}", show)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        Number available = JsonPath.read(body, "$.available");
        Number held = JsonPath.read(body, "$.held");
        Number confirmed = JsonPath.read(body, "$.confirmed");
        assertThat(available.intValue() + held.intValue() + confirmed.intValue()).isEqualTo(total);
    }

    private List<Reply> race(int count, IndexedRequest request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.execute(index);
                }));
            }
            start.countDown();
            return collect(futures);
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Reply> collect(List<Future<Reply>> futures) throws Exception {
        List<Reply> replies = new ArrayList<>();
        for (Future<Reply> future : futures) {
            replies.add(future.get(180, TimeUnit.SECONDS));
        }
        return replies;
    }

    @FunctionalInterface
    private interface IndexedRequest {
        Reply execute(int index) throws Exception;
    }

    private record Reply(int status, String body) {
    }
}
