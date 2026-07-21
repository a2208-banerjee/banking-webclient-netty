package com.banking.payment.client;

import com.banking.payment.exception.*;
import com.banking.payment.model.CustomerDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;

/**
 * WebClient wrappers for account and customer services.
 *
 * onStatus() is WebClient's equivalent of Feign's ErrorDecoder.
 *
 * Key difference:
 *   Feign:     ErrorDecoder defined ONCE globally (in GlobalFeignConfig)
 *   WebClient: onStatus() defined PER-CALL at the call site
 *
 * This gives WebClient more flexibility (different handling per endpoint)
 * at the cost of more code at each call site.
 *
 * mTLS is completely transparent here — the SslContext configured
 * in NettyWebClientConfig is attached to the HttpClient which is used
 * by the WebClient. No mTLS code needed in this file at all.
 */
@Slf4j
@Component
public class AccountWebClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient accountClient;
    private final WebClient customerClient;

    public AccountWebClient(
            @Qualifier("accountServiceWebClient")  WebClient accountClient,
            @Qualifier("customerServiceWebClient") WebClient customerClient) {
        this.accountClient  = accountClient;
        this.customerClient = customerClient;
    }

    // ── Account Service calls ─────────────────────────────────────────────────

    public Mono<Map<String, Object>> checkBalance(String accountId, BigDecimal amount) {
        return accountClient.get()
                .uri("/api/accounts/{id}/balance-check?amount={amount}", accountId, amount)
                .retrieve()
                /**
                 * onStatus() — WebClient's equivalent of Feign's ErrorDecoder switch case.
                 *
                 * In Feign:     handled in BankingErrorDecoder.decode() for ALL calls
                 * In WebClient: handled here, specific to this call
                 *
                 * 401: mTLS client cert rejected by account-service
                 *      (cert expired, wrong CA, or client-auth: need but no cert sent)
                 */
                .onStatus(status -> status.value() == 401,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new UnauthorizedException(
                                        "account-service rejected mTLS client cert: " + body)))

                .onStatus(status -> status.value() == 404,
                        response -> Mono.error(new ResourceNotFoundException(accountId)))

                .onStatus(status -> status.value() == 503,
                        response -> Mono.error(new ServiceUnavailableException("account-service (503)")))

                .onStatus(HttpStatusCode::is5xxServerError,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new ServiceUnavailableException(
                                        "account-service: " + body)))

                .bodyToMono(MAP_TYPE)
                .doOnSuccess(r -> log.debug("[Netty] Balance check OK — account: {}", accountId))
                .doOnError(e -> log.error("[Netty] Balance check failed: {}", e.getMessage()));
    }

    public Mono<Map<String, Object>> debit(String accountId, BigDecimal amount) {
        return accountClient.post()
                .uri("/api/accounts/{id}/debit", accountId)
                .bodyValue(Map.of("amount", amount))
                .retrieve()
                .onStatus(status -> status.value() == 401,
                        response -> Mono.error(new UnauthorizedException("account-service mTLS")))
                .onStatus(status -> status.value() == 404,
                        response -> Mono.error(new ResourceNotFoundException(accountId)))
                .onStatus(status -> status.value() == 409,
                        response -> response.bodyToMono(MAP_TYPE)
                                .map(body -> new DuplicatePaymentException(
                                        String.valueOf(body.getOrDefault("idempotencyKey", "")))))
                .onStatus(HttpStatusCode::is5xxServerError,
                        response -> Mono.error(new ServiceUnavailableException("account-service")))
                .bodyToMono(MAP_TYPE)
                .doOnSuccess(r -> log.info("[Netty] Debit OK — account: {}", accountId));
    }

    public Mono<Map<String, Object>> credit(String accountId, BigDecimal amount) {
        return accountClient.post()
                .uri("/api/accounts/{id}/credit", accountId)
                .bodyValue(Map.of("amount", amount))
                .retrieve()
                .onStatus(status -> status.value() == 401,
                        response -> Mono.error(new UnauthorizedException("account-service mTLS")))
                .onStatus(status -> status.value() == 404,
                        response -> Mono.error(new ResourceNotFoundException(accountId)))
                .onStatus(HttpStatusCode::is5xxServerError,
                        response -> Mono.error(new ServiceUnavailableException("account-service")))
                .bodyToMono(MAP_TYPE)
                .doOnSuccess(r -> log.info("[Netty] Credit OK — account: {}", accountId));
    }

    // ── Customer Service calls ────────────────────────────────────────────────

    public Mono<CustomerDto> getCustomerByAccountId(String accountId) {
        return customerClient.get()
                .uri("/api/customers/account/{accountId}", accountId)
                .retrieve()
                .onStatus(status -> status.value() == 401,
                        response -> Mono.error(new UnauthorizedException(
                                "customer-service rejected mTLS client cert")))
                .onStatus(status -> status.value() == 404,
                        response -> Mono.error(new ResourceNotFoundException(
                                "Customer for account: " + accountId)))
                .onStatus(status -> status.value() == 503,
                        response -> Mono.error(new ServiceUnavailableException("customer-service (503)")))
                .onStatus(HttpStatusCode::is5xxServerError,
                        response -> Mono.error(new ServiceUnavailableException("customer-service")))
                .bodyToMono(CustomerDto.class)
                .doOnSuccess(c -> log.info("[Netty] Customer fetched: {}", c.getFullName()));
    }
}
