package com.example.licensing.generator;

import com.example.licensing.issuer.LicenseIssuingService;
import com.example.licensing.issuer.RequestParser;
import com.example.licensing.issuer.keystore.KeyRing;
import com.example.licensing.issuer.keystore.SigningKeyStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns expected problems into a status and a message the person at the page can act on. */
@RestControllerAdvice
class ApiErrorHandler {

    @ExceptionHandler(RequestParser.RequestParseException.class)
    ResponseEntity<ApiError> badRequestText(RequestParser.RequestParseException e) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_request_text", e.getMessage()));
    }

    @ExceptionHandler(LicenseIssuingService.IssueValidationException.class)
    ResponseEntity<ApiError> badInput(LicenseIssuingService.IssueValidationException e) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_input", e.getMessage()));
    }

    @ExceptionHandler(LicenseIssuingService.NoActiveKeyException.class)
    ResponseEntity<ApiError> noActiveKey(LicenseIssuingService.NoActiveKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("no_active_key", e.getMessage()));
    }

    @ExceptionHandler(KeyRing.KeyRingException.class)
    ResponseEntity<ApiError> keyRing(KeyRing.KeyRingException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("key_conflict", e.getMessage()));
    }

    /** Invalid key id, wrong keystore password, too short reserve password, ... */
    @ExceptionHandler(SigningKeyStore.SigningKeyStoreException.class)
    ResponseEntity<ApiError> keystore(SigningKeyStore.SigningKeyStoreException e) {
        return ResponseEntity.badRequest().body(new ApiError("key_error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> illegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_input", e.getMessage()));
    }

    record ApiError(String error, String message) {
    }
}
