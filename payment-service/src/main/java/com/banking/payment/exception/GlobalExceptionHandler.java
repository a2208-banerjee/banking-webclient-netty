package com.banking.payment.exception;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.Map;
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(UnauthorizedException.class)
    public Mono<ResponseEntity<Map<String,Object>>> handleAuth(UnauthorizedException ex, ServerWebExchange ex2) {
        log.error("mTLS auth failure: {}", ex.getMessage());
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage(), ex2);
    }
    @ExceptionHandler(ResourceNotFoundException.class)
    public Mono<ResponseEntity<Map<String,Object>>> handleNotFound(ResourceNotFoundException ex, ServerWebExchange ex2) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), ex2);
    }
    @ExceptionHandler(ServiceUnavailableException.class)
    public Mono<ResponseEntity<Map<String,Object>>> handleUnavailable(ServiceUnavailableException ex, ServerWebExchange ex2) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", ex.getMessage(), ex2);
    }
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String,Object>>> handleGeneric(Exception ex, ServerWebExchange ex2) {
        log.error("Unhandled: {}", ex.getMessage(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage(), ex2);
    }
    private Mono<ResponseEntity<Map<String,Object>>> error(HttpStatus s, String code, String msg, ServerWebExchange ex) {
        return Mono.just(ResponseEntity.status(s).body(Map.of("error", code, "message", msg,
            "path", ex.getRequest().getPath().value(), "timestamp", LocalDateTime.now().toString())));
    }
}
