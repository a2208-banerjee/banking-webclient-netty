package com.banking.payment.service;

import com.banking.payment.client.AccountWebClient;
import com.banking.payment.exception.*;
import com.banking.payment.model.Payment;
import com.banking.payment.model.PaymentRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reactive payment orchestration using WebClient over Reactor Netty.
 *
 * ── Key difference vs App 1 (Feign + Apache) ──────────────────────────────
 *
 * App 1 (Feign):    SEQUENTIAL — each step waits for the previous
 *   1. getCustomer()   → wait 50ms
 *   2. checkBalance()  → wait 30ms
 *   3. debit()         → wait 20ms
 *   Total: ~100ms
 *
 * App 2 (WebClient): PARALLEL with Mono.zip() — steps 1+2 run simultaneously
 *   1+2. zip(getCustomer(), checkBalance()) → wait max(50ms, 30ms) = 50ms
 *   3.   debit()                            → wait 20ms
 *   Total: ~70ms  ← 30% faster for this flow
 *
 * The Reactor Context is used to propagate the idempotency key to all
 * WebClient calls without threading overhead (no ThreadLocal).
 *
 * mTLS is completely invisible here — handled by NettyWebClientConfig.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final AccountWebClient accountWebClient;
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    public Mono<Payment> processPayment(PaymentRequest request) {
        String paymentId      = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String idempotencyKey = UUID.randomUUID().toString();

        log.info("Processing payment {} via WebClient + Reactor Netty (mTLS)", paymentId);

        Payment payment = Payment.builder()
                .paymentId(paymentId)
                .idempotencyKey(idempotencyKey)
                .sourceAccountId(request.getSourceAccountId())
                .destinationAccountId(request.getDestinationAccountId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .reference(request.getReference())
                .status("PROCESSING")
                .httpClientUsed("Reactor Netty + WebClient (mTLS)")
                .createdAt(LocalDateTime.now())
                .build();

        payments.put(paymentId, payment);

        return orchestrate(request, payment)
                // Reactor Context — idempotency key propagated to ALL WebClient calls
                // via idempotencyKeyFilter in NettyWebClientConfig.
                // This replaces ThreadLocal which breaks with async code.
                .contextWrite(Context.of("idempotencyKey", idempotencyKey));
    }

    private Mono<Payment> orchestrate(PaymentRequest request, Payment payment) {

        // ── PARALLEL: customer fetch + balance check at the same time ──────────
        // Mono.zip fires both calls simultaneously and waits for BOTH to complete.
        // In App 1 (Feign), these run sequentially.
        // This is the primary performance advantage of reactive WebClient.
        return Mono.zip(
                accountWebClient.getCustomerByAccountId(request.getSourceAccountId()),
                accountWebClient.checkBalance(request.getSourceAccountId(), request.getAmount())
        )
        .flatMap(tuple -> {
            var customer = tuple.getT1();
            var balance  = tuple.getT2();

            payment.setCustomerName(customer.getFullName());
            payment.setCustomerNumber(customer.getCustomerNumber());

            Boolean sufficient = (Boolean) balance.getOrDefault("sufficient", false);
            if (!Boolean.TRUE.equals(sufficient)) {
                return Mono.error(new InsufficientFundsException(request.getSourceAccountId()));
            }

            log.info("Step 1+2 parallel complete — customer: {}, funds: sufficient",
                    customer.getFullName());

            // ── Sequential: debit then credit (order matters) ─────────────────
            return accountWebClient.debit(request.getSourceAccountId(), request.getAmount())
                    .then(accountWebClient.credit(
                            request.getDestinationAccountId(), request.getAmount()))
                    .thenReturn(payment);
        })
        .map(p -> {
            p.setStatus("COMPLETED");
            p.setProcessedAt(LocalDateTime.now());
            log.info("Payment {} completed via Reactor Netty", p.getPaymentId());
            return p;
        })
        .onErrorResume(InsufficientFundsException.class, e ->
                Mono.just(fail(payment, e.getMessage())))
        .onErrorResume(DuplicatePaymentException.class, e ->
                Mono.just(fail(payment, "Duplicate: " + e.getMessage())))
        .onErrorResume(UnauthorizedException.class, e -> {
            log.error("mTLS auth failure — check client.p12: {}", e.getMessage());
            return Mono.just(fail(payment, "mTLS auth failed: " + e.getMessage()));
        })
        .onErrorResume(ServiceUnavailableException.class, e ->
                Mono.just(fail(payment, "Service unavailable: " + e.getMessage())))
        .onErrorResume(Exception.class, e ->
                Mono.just(fail(payment, "Unexpected: " + e.getMessage())));
    }

    public Mono<Payment> getPaymentById(String id) {
        return Mono.justOrEmpty(payments.get(id));
    }

    public Flux<Payment> getAllPayments() {
        return Flux.fromIterable(payments.values());
    }

    private Payment fail(Payment payment, String reason) {
        payment.setStatus("FAILED");
        payment.setFailureReason(reason);
        payment.setProcessedAt(LocalDateTime.now());
        return payment;
    }
}
