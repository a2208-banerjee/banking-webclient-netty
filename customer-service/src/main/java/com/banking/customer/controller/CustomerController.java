package com.banking.customer.controller;
import com.banking.customer.model.Customer;
import com.banking.customer.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService customerService;
    @GetMapping("/account/{accountId}")
    public Mono<ResponseEntity<Customer>> getByAccountId(@PathVariable String accountId) {
        return customerService.getByAccountId(accountId)
            .map(ResponseEntity::ok).defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
