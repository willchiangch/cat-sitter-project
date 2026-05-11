package com.petsitter.application.exception;

public class AuthPlanLimitException extends RuntimeException {
    public AuthPlanLimitException(String message) {
        super(message);
    }
}
