package com.quiktech.pos.exception;

public class PaymentAccountUnavailableException extends RuntimeException {
    public PaymentAccountUnavailableException() {
        super("Subscription payments are temporarily unavailable. Please contact support.");
    }
}
