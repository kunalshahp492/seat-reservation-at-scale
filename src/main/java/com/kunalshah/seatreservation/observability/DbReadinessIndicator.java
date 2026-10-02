package com.kunalshah.seatreservation.observability;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("dbReadiness")
public class DbReadinessIndicator implements HealthIndicator {
    private final DataSource dataSource;

    public DbReadinessIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? Health.up().build() : Health.down().build();
        } catch (SQLException failure) {
            return Health.down(failure).build();
        }
    }
}
