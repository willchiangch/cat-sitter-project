package com.petsitter.application.exception;

import org.springframework.http.HttpStatus;

public class AccountDeactivationException extends RuntimeException {
    private final HttpStatus status;
    private final String error;

    public AccountDeactivationException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
