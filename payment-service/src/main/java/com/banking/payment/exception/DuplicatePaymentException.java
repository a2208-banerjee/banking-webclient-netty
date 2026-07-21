package com.banking.payment.exception;
public class DuplicatePaymentException extends RuntimeException {
    public DuplicatePaymentException(String msg) { super(msg); }
}
