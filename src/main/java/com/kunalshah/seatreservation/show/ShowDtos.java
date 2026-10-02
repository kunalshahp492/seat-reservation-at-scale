package com.kunalshah.seatreservation.show;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class ShowDtos {
    private ShowDtos() {
    }

    public record ShowCreateRequest(
            @NotBlank @Size(max = 200) String name,
            @NotEmpty List<String> seats,
            @JsonProperty("price_paise") @NotNull @PositiveOrZero Long pricePaise,
            @JsonProperty("per_user_limit") @Positive Integer perUserLimit) {
    }

    public record SeatView(
            @JsonProperty("seat_label") String seatLabel,
            String state) {
    }

    public record ShowView(
            UUID id,
            String name,
            @JsonProperty("price_paise") long pricePaise,
            @JsonProperty("per_user_limit") int perUserLimit,
            @JsonProperty("total_seats") int totalSeats,
            int available,
            int held,
            int confirmed,
            List<SeatView> seats) {
    }
}
