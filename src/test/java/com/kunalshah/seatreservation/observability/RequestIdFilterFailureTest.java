package com.kunalshah.seatreservation.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterFailureTest {
    @Test
    void unexpectedDownstreamFailureIsLoggedAsFailureWithoutExceptionText() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shows/123/reserve");
            request.addHeader("X-Request-Id", "failure-test");
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThatThrownBy(() -> new RequestIdFilter().doFilter(
                    request, response, (ignoredRequest, ignoredResponse) -> {
                        throw new ServletException("secret-never-log");
                    }))
                    .isInstanceOf(ServletException.class);

            ILoggingEvent event = appender.list.stream()
                    .filter(item -> "failure-test".equals(item.getMDCPropertyMap().get("request_id")))
                    .findFirst().orElseThrow();
            assertThat(event.getMDCPropertyMap())
                    .containsEntry("status", "500")
                    .containsEntry("outcome_reason", "internal_error");
            assertThat(event.getFormattedMessage()).doesNotContain("secret-never-log");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
