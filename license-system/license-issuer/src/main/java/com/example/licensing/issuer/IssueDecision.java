package com.example.licensing.issuer;

import java.time.Duration;
import java.util.Set;

/**
 * What an operator decides when granting a request: entitlement. Never
 * derived from the request itself - the request only says who is asking.
 */
public record IssueDecision(String customer, Set<String> modules, int maxUsers, Duration validity) {

    public IssueDecision {
        if (customer == null || customer.isBlank()) {
            throw new IllegalArgumentException("customer is required");
        }
        modules = modules == null ? Set.of() : Set.copyOf(modules);
        if (maxUsers < 0) {
            throw new IllegalArgumentException("maxUsers must not be negative");
        }
        if (validity == null || validity.isNegative() || validity.isZero()) {
            throw new IllegalArgumentException("validity must be positive");
        }
    }
}
