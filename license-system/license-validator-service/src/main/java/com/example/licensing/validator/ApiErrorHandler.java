package com.example.licensing.validator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrorHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_input", e.getMessage()));
    }

    record ApiError(String error, String message) {
    }
}
