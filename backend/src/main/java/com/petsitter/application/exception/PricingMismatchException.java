package com.petsitter.application.exception;

public class PricingMismatchException extends RuntimeException {
    public PricingMismatchException(String message) {
        super(message);
    }
}
