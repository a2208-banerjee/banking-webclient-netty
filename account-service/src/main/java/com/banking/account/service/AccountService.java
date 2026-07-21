package com.banking.account.service;
import com.banking.account.model.Account;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
@Service
public class AccountService {
    private final Map<String, Account> store = new ConcurrentHashMap<>();
    public AccountService() {
        store.put("ACC001", Account.builder().accountId("ACC001").accountNumber("12345678").sortCode("20-00-00")
            .accountHolder("Priya Sharma").accountType("CURRENT").balance(new BigDecimal("5420.75")).currency("GBP").status("ACTIVE").build());
        store.put("ACC002", Account.builder().accountId("ACC002").accountNumber("87654321").sortCode("30-00-00")
            .accountHolder("Raj Patel").accountType("SAVINGS").balance(new BigDecimal("12850.00")).currency("GBP").status("ACTIVE").build());
    }
    public Flux<Account> getAll() { return Flux.fromIterable(store.values()); }
    public Mono<Account> getById(String id) { return Mono.justOrEmpty(store.get(id)); }
    public Mono<Boolean> hasSufficientFunds(String id, BigDecimal amount) {
        return getById(id).map(a -> a.getBalance().compareTo(amount) >= 0).defaultIfEmpty(false);
    }
}
