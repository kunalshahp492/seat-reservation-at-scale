package com.kunalshah.seatreservation.api;

import java.util.Map;

import com.kunalshah.seatreservation.observability.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> domainError(
            ApiException error, HttpServletRequest request) {
        request.setAttribute(RequestIdFilter.OUTCOME_REASON, error.error());
        return ResponseEntity.status(error.status())
                .body(Map.of("error", error.error()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalidInput(
            Exception ignored, HttpServletRequest request) {
        request.setAttribute(RequestIdFilter.OUTCOME_REASON, "invalid_request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "invalid_request"));
    }
}
