package com.banking.payment.exception;
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String msg) { super(msg); }
}
