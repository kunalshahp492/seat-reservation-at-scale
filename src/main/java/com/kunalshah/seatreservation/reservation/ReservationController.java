package com.kunalshah.seatreservation.reservation;

import java.util.Map;
import java.util.UUID;

import com.kunalshah.seatreservation.reservation.ReservationDtos.ReservationResult;
import com.kunalshah.seatreservation.reservation.ReservationDtos.ReserveRequest;
import com.kunalshah.seatreservation.observability.RequestIdFilter;
import com.kunalshah.seatreservation.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {
    private final ReservationService service;
    private final JwtService jwtService;

    public ReservationController(ReservationService service, JwtService jwtService) {
        this.service = service;
        this.jwtService = jwtService;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<?> reserve(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReserveRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(RequestIdFilter.SHOW_ID, id);
        ReservationResult result = service.reserve(id, jwtService.subject(jwt), request);
        httpRequest.setAttribute(RequestIdFilter.OUTCOME_REASON,
                result.error() == null ? (result.httpStatus() == 200 ? "idempotent_replay" : "confirmed")
                        : result.error());
        if (result.reservation() != null) {
            httpRequest.setAttribute(RequestIdFilter.RESERVATION_ID, result.reservation().id());
        }
        Object body = result.reservation() != null
                ? result.reservation()
                : Map.of("error", result.error());
        return ResponseEntity.status(result.httpStatus()).body(body);
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationDtos.ReservationView cancel(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(RequestIdFilter.RESERVATION_ID, id);
        ReservationDtos.ReservationView result = service.cancel(id, jwtService.subject(jwt));
        httpRequest.setAttribute(RequestIdFilter.SHOW_ID, result.showId());
        httpRequest.setAttribute(RequestIdFilter.OUTCOME_REASON, "cancelled");
        return result;
    }
}
