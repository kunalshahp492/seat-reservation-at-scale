package com.kunalshah.seatreservation.reservation;

import java.util.Map;
import java.util.UUID;

import com.kunalshah.seatreservation.reservation.ReservationDtos.ReservationResult;
import com.kunalshah.seatreservation.reservation.ReservationDtos.ReserveRequest;
import com.kunalshah.seatreservation.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
public class ReservationController {
    private final ReservationService service;
    private final JwtService jwtService;

    public ReservationController(ReservationService service, JwtService jwtService) {
        this.service = service;
        this.jwtService = jwtService;
    }

    @PostMapping("/{id}/reserve")
    public ResponseEntity<?> reserve(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReserveRequest request) {
        ReservationResult result = service.reserve(id, jwtService.subject(jwt), request);
        Object body = result.reservation() != null
                ? result.reservation()
                : Map.of("error", result.error());
        return ResponseEntity.status(result.httpStatus()).body(body);
    }
}
