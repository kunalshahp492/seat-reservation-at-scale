package com.kunalshah.seatreservation.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class DbReadinessIndicatorTest {
    @Test
    void reportsDownWhenDatabaseCannotBeReached() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("database unavailable"));

        assertThat(new DbReadinessIndicator(dataSource).health().getStatus())
                .isEqualTo(Status.DOWN);
    }
}
