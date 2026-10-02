package com.kunalshah.seatreservation.show;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.kunalshah.seatreservation.api.ApiException;
import com.kunalshah.seatreservation.show.ShowDtos.SeatView;
import com.kunalshah.seatreservation.show.ShowDtos.ShowCreateRequest;
import com.kunalshah.seatreservation.show.ShowDtos.ShowView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {
    private final ShowRepository repository;

    public ShowService(ShowRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ShowView create(ShowCreateRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()
                || request.name().length() > 200 || request.pricePaise() == null
                || request.pricePaise() < 0 || request.seats() == null
                || request.seats().isEmpty()
                || (request.perUserLimit() != null && request.perUserLimit() <= 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        }

        List<String> labels = new ArrayList<>(request.seats().size());
        Set<String> seen = new HashSet<>();
        for (String raw : request.seats()) {
            if (raw == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
            }
            String label = raw.trim();
            if (label.isEmpty() || label.length() > 80 || !seen.add(label)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
            }
            labels.add(label);
        }

        UUID id = UUID.randomUUID();
        repository.insert(id, request.name().trim(), request.pricePaise(),
                request.perUserLimit() == null ? 4 : request.perUserLimit(), labels.size());
        for (String label : labels) {
            repository.insertSeat(id, label);
        }
        return get(id);
    }

    public ShowView get(UUID showId) {
        List<ShowRepository.ShowSeatRow> rows = repository.findRows(showId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "show_not_found");
        }
        ShowRepository.ShowSeatRow first = rows.get(0);
        List<SeatView> seats = rows.stream()
                .map(row -> new SeatView(row.seatLabel(), row.state().toLowerCase(Locale.ROOT)))
                .toList();
        int available = (int) rows.stream()
                .filter(row -> row.state().equals("AVAILABLE")).count();
        int confirmed = (int) rows.stream()
                .filter(row -> row.state().equals("CONFIRMED")).count();
        return new ShowView(first.id(), first.name(), first.pricePaise(),
                first.perUserLimit(), first.totalSeats(), available, 0, confirmed, seats);
    }
}
