package com.kunalshah.seatreservation.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CancellationLockOrderTest {
    @Test
    void repeatedCancellationLocksSeatsInTheSameOrderAsReserve() {
        ReservationRepository repository = mock(ReservationRepository.class);
        ReservationService service = new ReservationService(
                repository, mock(IdempotencyRepository.class));
        UUID showId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        String owner = "owner";
        Instant now = Instant.now();

        when(repository.cancellationTarget(reservationId))
                .thenReturn(new ReservationRepository.CancellationTarget(showId, owner));
        // A locale-aware database can return this order, opposite to Java String.compareTo.
        when(repository.reservationLabels(reservationId)).thenReturn(List.of("a", "B"));
        when(repository.lockReservationState(reservationId)).thenReturn("CANCELLED");
        when(repository.findReservation(reservationId)).thenReturn(List.of(
                new ReservationRepository.ReservationRow(reservationId, showId, owner,
                        100, "CANCELLED", now, now, "a"),
                new ReservationRepository.ReservationRow(reservationId, showId, owner,
                        100, "CANCELLED", now, now, "B")));

        service.cancel(reservationId, owner);

        ArgumentCaptor<String> lockedLabels = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).lockSeat(eq(showId), lockedLabels.capture());
        assertThat(lockedLabels.getAllValues()).containsExactly("B", "a");
    }
}
