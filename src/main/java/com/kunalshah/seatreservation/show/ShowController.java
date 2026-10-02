package com.kunalshah.seatreservation.show;

import java.net.URI;
import java.util.UUID;

import com.kunalshah.seatreservation.observability.RequestIdFilter;
import com.kunalshah.seatreservation.show.ShowDtos.ShowCreateRequest;
import com.kunalshah.seatreservation.show.ShowDtos.ShowView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
public class ShowController {
    private final ShowService service;

    public ShowController(ShowService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ShowView> create(
            @Valid @RequestBody ShowCreateRequest request, HttpServletRequest httpRequest) {
        ShowView show = service.create(request);
        httpRequest.setAttribute(RequestIdFilter.SHOW_ID, show.id());
        return ResponseEntity.created(URI.create("/shows/" + show.id())).body(show);
    }

    @GetMapping("/{id}")
    public ShowView get(@PathVariable UUID id, HttpServletRequest httpRequest) {
        httpRequest.setAttribute(RequestIdFilter.SHOW_ID, id);
        return service.get(id);
    }
}
