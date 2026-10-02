package com.kunalshah.seatreservation.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.DuplicateKeyException;

@SpringBootTest
class SchemaIT {
    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registerIfPresent(registry, "spring.datasource.url", "TEST_DB_URL");
        registerIfPresent(registry, "spring.datasource.username", "TEST_DB_USER");
        registerIfPresent(registry, "spring.datasource.password", "TEST_DB_PASSWORD");
    }

    private static void registerIfPresent(DynamicPropertyRegistry registry, String property, String env) {
        String value = System.getenv(env);
        if (value != null && !value.isBlank()) {
            registry.add(property, () -> value);
        }
    }

    @Test
    void createsAllDomainTablesAndDefaultLimit() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);
        assertThat(tables).contains(
                "shows", "show_seats", "show_user_state", "reservations",
                "reservation_seats", "idempotency_requests");

        Integer defaultLimit = jdbc.queryForObject(
                "select column_default::integer from information_schema.columns "
                        + "where table_schema = 'public' and table_name = 'shows' "
                        + "and column_name = 'per_user_limit'",
                Integer.class);
        assertThat(defaultLimit).isEqualTo(4);
    }

    @Test
    void seatLabelIsUniqueWithinShow() {
        UUID showId = UUID.randomUUID();
        jdbc.update("insert into shows (id, name, price_paise, total_seats) values (?, ?, ?, ?)",
                showId, "schema test", 100L, 1);
        jdbc.update("insert into show_seats (show_id, seat_label) values (?, ?)", showId, "A1");

        assertThatThrownBy(() -> jdbc.update(
                "insert into show_seats (show_id, seat_label) values (?, ?)", showId, "A1"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void idempotencyKeyIsUniqueForUser() {
        UUID showId = UUID.randomUUID();
        jdbc.update("insert into shows (id, name, price_paise, total_seats) values (?, ?, ?, ?)",
                showId, "schema test", 100L, 1);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        jdbc.update(
                "insert into idempotency_requests (id, user_id, idempotency_key, show_id, request_fingerprint) "
                        + "values (?, ?, ?, ?, ?)",
                firstId, "user-1", "same-key", showId, "fingerprint");

        assertThatThrownBy(() -> jdbc.update(
                "insert into idempotency_requests (id, user_id, idempotency_key, show_id, request_fingerprint) "
                        + "values (?, ?, ?, ?, ?)",
                secondId, "user-1", "same-key", showId, "fingerprint"))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
