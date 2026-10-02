package com.kunalshah.seatreservation.observability;

import java.util.Map;

import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final DbReadinessIndicator database;

    public HealthController(DbReadinessIndicator database) {
        this.database = database;
    }

    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        boolean up = database.health().getStatus().equals(Status.UP);
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", up ? "UP" : "DOWN"));
    }
}
