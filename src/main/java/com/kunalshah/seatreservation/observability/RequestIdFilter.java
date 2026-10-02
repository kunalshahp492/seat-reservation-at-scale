package com.kunalshah.seatreservation.observability;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String OUTCOME_REASON = "outcome_reason";
    public static final String SHOW_ID = "show_id";
    public static final String RESERVATION_ID = "reservation_id";
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader("X-Request-Id");
        String requestId = incoming != null && incoming.matches("[A-Za-z0-9-]{1,64}")
                ? incoming : UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        long start = System.nanoTime();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("request_id", requestId)) {
            try {
                chain.doFilter(request, response);
            } finally {
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = pattern == null ? request.getRequestURI() : pattern.toString();
                MDC.put("route", route);
                MDC.put("status", Integer.toString(response.getStatus()));
                MDC.put("latency_ms", Long.toString((System.nanoTime() - start) / 1_000_000));
                copyAttribute(request, OUTCOME_REASON);
                copyAttribute(request, SHOW_ID);
                copyAttribute(request, RESERVATION_ID);
                LOG.info("request completed");
                MDC.remove("route");
                MDC.remove("status");
                MDC.remove("latency_ms");
                MDC.remove(OUTCOME_REASON);
                MDC.remove(SHOW_ID);
                MDC.remove(RESERVATION_ID);
            }
        }
    }

    private static void copyAttribute(HttpServletRequest request, String key) {
        Object value = request.getAttribute(key);
        if (value != null) {
            MDC.put(key, value.toString());
        }
    }
}
