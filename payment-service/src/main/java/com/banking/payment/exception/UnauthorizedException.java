package com.banking.payment.exception;
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String msg) { super(msg); }
}
