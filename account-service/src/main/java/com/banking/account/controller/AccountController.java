package com.banking.account.controller;
import com.banking.account.model.Account;
import com.banking.account.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.util.Map;
@Slf4j
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final AccountService accountService;
    @GetMapping
    public Flux<Account> getAll(ServerHttpRequest request) {
        log.info("[mTLS] Request from: {}", request.getRemoteAddress());
        return accountService.getAll();
    }
    @GetMapping("/{id}")
    public Mono<ResponseEntity<Account>> getById(@PathVariable String id) {
        return accountService.getById(id).map(ResponseEntity::ok).defaultIfEmpty(ResponseEntity.notFound().build());
    }
    @GetMapping("/{id}/balance-check")
    public Mono<Map<String, Object>> checkBalance(@PathVariable String id, @RequestParam BigDecimal amount,
            @RequestHeader(value="X-Idempotency-Key", required=false) String key) {
        log.info("Balance check — account: {}, amount: {}, key: {}", id, amount, key);
        return accountService.hasSufficientFunds(id, amount)
                .map(s -> (Map<String, Object>) Map.of("accountId", id, "sufficient", s));
    }
}
