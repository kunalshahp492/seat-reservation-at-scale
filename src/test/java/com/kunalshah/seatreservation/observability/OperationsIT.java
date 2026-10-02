package com.kunalshah.seatreservation.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class OperationsIT {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void healthMetricsAndStructuredLogsReflectCommittedOutcomes(CapturedOutput output)
            throws Exception {
        assertThat(response(get("/health/live")).getResponse().getStatus()).isEqualTo(200);
        assertThat(response(get("/health/ready")).getResponse().getStatus()).isEqualTo(200);

        String showId = createShow();
        MvcResult confirmed = reserve(showId, "metrics-owner", "success", "A1");
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(201);
        assertThat(reserve(showId, "metrics-loser", "decline", "A1")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(reserve(showId, "metrics-owner", "success", "A1")
                .getResponse().getStatus()).isEqualTo(200);
        String reservationId = JsonPath.read(
                confirmed.getResponse().getContentAsString(), "$.id");
        assertThat(response(post("/reservations/{id}/cancel", reservationId)
                .with(jwt().jwt(token -> token.subject("metrics-owner"))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult scrape = response(get("/actuator/prometheus"));
        assertThat(scrape.getResponse().getStatus()).isEqualTo(200);
        String metrics = scrape.getResponse().getContentAsString();
        long successes = jdbc.queryForObject(
                "select count(*) from idempotency_requests where http_status = 201", Long.class);
        long declines = jdbc.queryForObject(
                "select count(*) from idempotency_requests where decline_reason = 'seat_taken'", Long.class);
        long replays = jdbc.queryForObject(
                "select coalesce(sum(replay_count), 0) from idempotency_requests", Long.class);
        long available = jdbc.queryForObject(
                "select count(*) from show_seats where state = 'AVAILABLE'", Long.class);
        assertThat(sample(metrics, "reservations_confirmed_total", null)).isEqualTo(successes);
        assertThat(sample(metrics, "reservations_declined_total", "reason=\"seat_taken\""))
                .isEqualTo(declines);
        assertThat(sample(metrics, "idempotency_replays_total", null)).isEqualTo(replays);
        assertThat(sample(metrics, "seats_available", null)).isEqualTo(available);

        String marker = "operations-" + UUID.randomUUID();
        response(get("/shows/{id}", showId).header("X-Request-Id", marker)
                .header("Authorization", "Bearer secret-never-log"));
        assertThat(output.getOut()).contains("\"request_id\":\"" + marker + "\"");
        assertThat(output.getOut()).contains("\"route\":\"/shows/{id}\"");
        assertThat(output.getOut()).doesNotContain("secret-never-log");
    }

    private String createShow() throws Exception {
        MvcResult result = response(post("/shows")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"operations-" + UUID.randomUUID()
                        + "\",\"seats\":[\"A1\",\"A2\"],\"price_paise\":100}"));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private MvcResult reserve(String showId, String user, String key, String seat) throws Exception {
        return response(post("/shows/{id}/reserve", showId)
                .with(jwt().jwt(token -> token.subject(user))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":[\"" + seat + "\"],\"idempotency_key\":\"" + key + "\"}"));
    }

    private MvcResult response(org.springframework.test.web.servlet.RequestBuilder request)
            throws Exception {
        return mvc.perform(request).andReturn();
    }

    private static double sample(String scrape, String name, String label) {
        return List.of(scrape.split("\\R")).stream()
                .filter(line -> line.startsWith(name + (label == null ? " " : "{")))
                .filter(line -> label == null || line.contains(label))
                .map(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst().orElseThrow(() -> new AssertionError("Missing metric " + name));
    }
}
