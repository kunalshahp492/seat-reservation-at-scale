package com.kunalshah.seatreservation.reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public final class ReservationDtos {
    private ReservationDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReserveRequest(
            @NotEmpty List<String> seats,
            @JsonProperty("idempotency_key") @NotBlank String idempotencyKey) {
    }

    public record ReservationView(
            UUID id,
            @JsonProperty("show_id") UUID showId,
            @JsonProperty("user_id") String userId,
            List<String> seats,
            @JsonProperty("amount_paise") long amountPaise,
            String status,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("cancelled_at") Instant cancelledAt) {
    }

    public record ReservationResult(int httpStatus, ReservationView reservation, String error) {
        public static ReservationResult confirmed(ReservationView reservation) {
            return new ReservationResult(201, reservation, null);
        }

        public static ReservationResult replay(ReservationView reservation) {
            return new ReservationResult(200, reservation, null);
        }

        public static ReservationResult declined(int status, String error) {
            return new ReservationResult(status, null, error);
        }
    }
}
