import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Run with: java scripts/Burst.java BASE_URL ADMIN_JWT [REQUESTS] [--smoke]. */
public class Burst {
    private final String baseUrl;
    private final HttpClient client;
    private final ExecutorService executor;

    private Burst(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.executor = Executors.newFixedThreadPool(64);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .executor(executor)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException(
                    "Usage: java scripts/Burst.java BASE_URL ADMIN_JWT [REQUESTS] [--smoke]");
        }
        int requests = args.length >= 3 && !args[2].equals("--smoke")
                ? Integer.parseInt(args[2]) : 20_000;
        if (requests < 8) {
            throw new IllegalArgumentException("REQUESTS must be at least 8");
        }
        Burst burst = new Burst(args[0]);
        try {
            burst.run(args[1], requests);
        } finally {
            burst.executor.shutdownNow();
        }
    }

    private void run(String adminToken, int requests) throws Exception {
        String hotShow = createShow(adminToken, "hot", List.of("H1"));
        String replayShow = createShow(adminToken, "replay", List.of("R1"));
        String limitShow = createShow(adminToken, "limit",
                List.of("L1", "L2", "L3", "L4", "L5"));
        String overlapShow = createShow(adminToken, "overlap", List.of("M1", "M2", "M3"));

        // All token issuance and scenario setup happens before the measured burst.
        List<String> guestTokens = new ArrayList<>();
        for (int i = 0; i < Math.min(requests, 64); i++) {
            guestTokens.add(field(send("POST", "/auth/guest", null, null), "token"));
        }
        String replayToken = guestTokens.get(0);
        String limitToken = guestTokens.get(1);
        expectStatus(sendReserve(replayShow, replayToken, "replay-seed", List.of("R1")), 201);
        expectStatus(sendReserve(limitShow, limitToken, "limit-seed",
                List.of("L1", "L2", "L3", "L4")), 201);

        int hotCount = Math.max(2, requests * 60 / 100);
        int replayCount = Math.max(1, requests * 10 / 100);
        int limitCount = Math.max(1, requests * 10 / 100);
        int overlapCount = requests - hotCount - replayCount - limitCount;
        if (overlapCount < 2) {
            throw new IllegalArgumentException("REQUESTS too small for overlap scenario");
        }

        List<Attempt> attempts = new ArrayList<>(requests);
        for (int i = 0; i < hotCount; i++) {
            attempts.add(new Attempt("hot", hotShow,
                    guestTokens.get(i % guestTokens.size()), "hot-" + i, List.of("H1")));
        }
        for (int i = 0; i < replayCount; i++) {
            attempts.add(new Attempt("replay", replayShow, replayToken,
                    "replay-seed", List.of("R1")));
        }
        for (int i = 0; i < limitCount; i++) {
            attempts.add(new Attempt("limit", limitShow, limitToken,
                    "limit-" + i, List.of("L5")));
        }
        for (int i = 0; i < overlapCount; i++) {
            attempts.add(new Attempt("overlap", overlapShow,
                    guestTokens.get((i + 2) % guestTokens.size()), "overlap-" + i,
                    i % 2 == 0 ? List.of("M1", "M2") : List.of("M2", "M3")));
        }
        Collections.shuffle(attempts);

        Counts counts = new Counts();
        long started = System.nanoTime();
        // A bounded asynchronous window sends genuine races without exhausting a small host.
        for (int offset = 0; offset < attempts.size(); offset += 64) {
            List<CompletableFuture<Void>> active = new ArrayList<>();
            for (Attempt attempt : attempts.subList(offset, Math.min(offset + 64, attempts.size()))) {
                active.add(sendReserveAsync(attempt)
                        .handle((response, failure) -> {
                            counts.record(attempt, response, failure);
                            return null;
                        }));
            }
            CompletableFuture.allOf(active.toArray(CompletableFuture[]::new)).join();
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        ShowCounts hot = readCounts(hotShow);
        ShowCounts replay = readCounts(replayShow);
        ShowCounts limit = readCounts(limitShow);
        ShowCounts overlap = readCounts(overlapShow);
        System.out.printf("requests=%d elapsed_ms=%d new_confirmed=%d replay=%d "
                        + "seat_taken=%d per_user_limit=%d other_4xx=%d "
                        + "5xx=%d transport_errors=%d%n",
                requests, elapsedMillis, counts.confirmed, counts.replay,
                counts.seatTaken, counts.userLimit, counts.other4xx,
                counts.serverErrors, counts.transportErrors);
        System.out.printf("hot_new_confirmed=%d hot=%s replay_show=%s limit=%s overlap=%s%n",
                counts.hotConfirmed, hot, replay, limit, overlap);

        boolean reconciled = hot.matches(1, 0, 1) && replay.matches(1, 0, 1)
                && limit.matches(5, 1, 4) && overlap.matches(3, 1, 2);
        if (counts.total() != requests || counts.hotConfirmed != 1 || counts.confirmed != 2
                || counts.replay != replayCount || counts.userLimit != limitCount
                || counts.seatTaken != hotCount + overlapCount - 2
                || counts.other4xx != 0 || counts.serverErrors != 0
                || counts.transportErrors != 0 || !reconciled) {
            throw new IllegalStateException("Burst failed distribution or final seat reconciliation");
        }
        System.out.println("reconciliation=PASS");
    }

    private String createShow(String adminToken, String kind, List<String> seats)
            throws Exception {
        String body = "{\"name\":\"burst-" + kind + "-" + UUID.randomUUID()
                + "\",\"seats\":" + jsonSeats(seats) + ",\"price_paise\":100}";
        HttpResponse<String> response = send("POST", "/shows", adminToken, body);
        expectStatus(response, 201);
        return field(response, "id");
    }

    private ShowCounts readCounts(String showId) throws Exception {
        HttpResponse<String> response = send("GET", "/shows/" + showId, null, null);
        expectStatus(response, 200);
        return new ShowCounts(number(response, "total_seats"),
                number(response, "available"), number(response, "confirmed"),
                number(response, "held"));
    }

    private HttpResponse<String> sendReserve(
            String showId, String token, String key, List<String> seats) throws Exception {
        return send("POST", "/shows/" + showId + "/reserve", token,
                reserveBody(key, seats));
    }

    private CompletableFuture<HttpResponse<String>> sendReserveAsync(Attempt attempt) {
        return client.sendAsync(request("POST", "/shows/" + attempt.showId + "/reserve",
                attempt.token, reserveBody(attempt.key, attempt.seats)),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(
            String method, String path, String token, String body)
            throws IOException, InterruptedException {
        return client.send(request(method, path, token, body),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String token, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(120));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    private static String reserveBody(String key, List<String> seats) {
        return "{\"seats\":" + jsonSeats(seats) + ",\"idempotency_key\":\"" + key + "\"}";
    }

    private static String jsonSeats(List<String> seats) {
        return "[" + String.join(",", seats.stream().map(seat -> "\"" + seat + "\"").toList()) + "]";
    }

    private static String field(HttpResponse<String> response, String name) {
        Matcher match = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(response.body());
        if (!match.find()) {
            throw new IllegalStateException("Missing JSON field " + name + " in status "
                    + response.statusCode() + ": " + response.body());
        }
        return match.group(1);
    }

    private static int number(HttpResponse<String> response, String name) {
        Matcher match = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)")
                .matcher(response.body());
        if (!match.find()) {
            throw new IllegalStateException("Missing JSON number " + name + ": " + response.body());
        }
        return Integer.parseInt(match.group(1));
    }

    private static void expectStatus(HttpResponse<String> response, int status) {
        if (response.statusCode() != status) {
            throw new IllegalStateException("Expected HTTP " + status + ", got "
                    + response.statusCode() + ": " + response.body());
        }
    }

    private record Attempt(String kind, String showId, String token, String key,
                           List<String> seats) { }

    private record ShowCounts(int total, int available, int confirmed, int held) {
        boolean matches(int total, int available, int confirmed) {
            return this.total == total && this.available == available
                    && this.confirmed == confirmed && held == 0
                    && this.available + this.confirmed + held == total;
        }
    }

    private static final class Counts {
        private int confirmed;
        private int replay;
        private int seatTaken;
        private int userLimit;
        private int other4xx;
        private int serverErrors;
        private int transportErrors;
        private int hotConfirmed;

        synchronized void record(Attempt attempt, HttpResponse<String> response, Throwable failure) {
            if (failure != null) {
                transportErrors++;
                System.err.println("Transport error for " + attempt.kind + ": "
                        + (failure instanceof CompletionException ? failure.getCause() : failure));
                return;
            }
            int status = response.statusCode();
            if (status == 201) {
                confirmed++;
                if (attempt.kind.equals("hot")) hotConfirmed++;
            } else if (status == 200) {
                replay++;
            } else if (status == 409 && response.body().contains("\"seat_taken\"")) {
                seatTaken++;
            } else if (status == 409 && response.body().contains("\"per_user_limit\"")) {
                userLimit++;
            } else if (status >= 400 && status < 500) {
                other4xx++;
                System.err.println("Unexpected 4xx in " + attempt.kind + ": " + response.body());
            } else {
                serverErrors++;
                System.err.println("Unexpected response in " + attempt.kind + ": "
                        + status + " " + response.body());
            }
        }

        int total() {
            return confirmed + replay + seatTaken + userLimit + other4xx
                    + serverErrors + transportErrors;
        }
    }
}
