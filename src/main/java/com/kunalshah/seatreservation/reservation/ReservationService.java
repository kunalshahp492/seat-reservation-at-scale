package com.kunalshah.seatreservation.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.kunalshah.seatreservation.api.ApiException;
import com.kunalshah.seatreservation.reservation.ReservationDtos.ReservationResult;
import com.kunalshah.seatreservation.reservation.ReservationDtos.ReservationView;
import com.kunalshah.seatreservation.reservation.ReservationDtos.ReserveRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.TransactionStatus;

@Service
public class ReservationService {
    private final ReservationRepository reservations;
    private final IdempotencyRepository idempotency;

    public ReservationService(
            ReservationRepository reservations, IdempotencyRepository idempotency) {
        this.reservations = reservations;
        this.idempotency = idempotency;
    }

    @Transactional
    public ReservationResult reserve(UUID showId, String userId, ReserveRequest request) {
        List<String> labels = canonicalSeats(request);
        String key = request.idempotencyKey().trim();
        String fingerprint = fingerprint(showId, labels);

        ReservationRepository.ShowPolicy show = reservations.showPolicy(showId);
        if (show == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "show_not_found");
        }
        long amount;
        try {
            amount = Math.multiplyExact(show.pricePaise(), labels.size());
        } catch (ArithmeticException overflow) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        }

        UUID requestId = UUID.randomUUID();
        if (!idempotency.claim(requestId, userId, key, showId, fingerprint)) {
            IdempotencyRepository.Outcome earlier = idempotency.find(userId, key);
            if (!fingerprint.equals(earlier.fingerprint())) {
                return ReservationResult.declined(409, "idempotency_conflict");
            }
            if (earlier.httpStatus() == null) {
                throw new IllegalStateException("Committed idempotency request has no outcome");
            }
            idempotency.incrementReplay(userId, key);
            if (earlier.httpStatus() == 201) {
                return ReservationResult.replay(view(earlier.reservationId()));
            }
            return ReservationResult.declined(
                    earlier.httpStatus(), earlier.declineReason());
        }

        int currentCount = reservations.lockUserState(showId, userId);
        if ((long) currentCount + labels.size() > show.perUserLimit()) {
            idempotency.completeDecline(requestId, 409, "per_user_limit");
            return ReservationResult.declined(409, "per_user_limit");
        }

        TransactionStatus transaction = TransactionAspectSupport.currentTransactionStatus();
        Object savepoint = transaction.createSavepoint();
        UUID reservationId = UUID.randomUUID();
        reservations.insertReservation(reservationId, showId, userId, amount);
        for (String label : labels) {
            if (reservations.claimSeat(showId, label, reservationId) == 0) {
                transaction.rollbackToSavepoint(savepoint);
                transaction.releaseSavepoint(savepoint);
                String reason = reservations.seatExists(showId, label)
                        ? "seat_taken" : "seat_not_found";
                int status = reason.equals("seat_taken") ? 409 : 404;
                idempotency.completeDecline(requestId, status, reason);
                return ReservationResult.declined(status, reason);
            }
            reservations.attachSeat(reservationId, showId, label);
        }
        transaction.releaseSavepoint(savepoint);
        reservations.addActiveSeats(showId, userId, labels.size());
        idempotency.completeSuccess(requestId, reservationId);
        return ReservationResult.confirmed(view(reservationId));
    }

    public ReservationView view(UUID reservationId) {
        List<ReservationRepository.ReservationRow> rows =
                reservations.findReservation(reservationId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "reservation_not_found");
        }
        ReservationRepository.ReservationRow first = rows.get(0);
        return new ReservationView(first.id(), first.showId(), first.userId(),
                rows.stream().map(ReservationRepository.ReservationRow::seatLabel).toList(),
                first.amountPaise(), first.state().toLowerCase(Locale.ROOT),
                first.createdAt(), first.cancelledAt());
    }

    @Transactional
    public ReservationView cancel(UUID reservationId, String userId) {
        ReservationRepository.CancellationTarget target =
                reservations.cancellationTarget(reservationId);
        if (target == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "reservation_not_found");
        }
        if (!target.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_owner");
        }

        reservations.lockUserState(target.showId(), userId);
        List<String> labels = new ArrayList<>(reservations.reservationLabels(reservationId));
        if (labels.isEmpty()) {
            throw new IllegalStateException("Reservation has no seat links");
        }
        labels.sort(String::compareTo);
        for (String label : labels) {
            reservations.lockSeat(target.showId(), label);
        }
        String state = reservations.lockReservationState(reservationId);
        if (state.equals("CANCELLED")) {
            return view(reservationId);
        }
        if (!state.equals("CONFIRMED")) {
            throw new IllegalStateException("Unknown reservation state: " + state);
        }

        for (String label : labels) {
            if (reservations.releaseSeat(target.showId(), label, reservationId) != 1) {
                throw new IllegalStateException("Confirmed reservation lost seat ownership");
            }
        }
        if (reservations.markCancelled(reservationId) != 1
                || reservations.subtractActiveSeats(
                        target.showId(), userId, labels.size()) != 1) {
            throw new IllegalStateException("Cancellation count did not reconcile");
        }
        return view(reservationId);
    }

    private static List<String> canonicalSeats(ReserveRequest request) {
        if (request == null || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank()
                || request.idempotencyKey().trim().length() > 200
                || request.seats() == null || request.seats().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        List<String> labels = new ArrayList<>(request.seats().size());
        Set<String> unique = new HashSet<>();
        for (String raw : request.seats()) {
            if (raw == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
            }
            String label = raw.trim();
            if (label.isEmpty() || label.length() > 80 || !unique.add(label)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
            }
            labels.add(label);
        }
        labels.sort(String::compareTo);
        return labels;
    }

    private static String fingerprint(UUID showId, List<String> labels) {
        StringBuilder canonical = new StringBuilder(showId.toString()).append(':');
        for (String label : labels) {
            canonical.append(label.length()).append(':').append(label);
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
