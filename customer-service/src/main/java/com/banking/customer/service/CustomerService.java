package com.banking.customer.service;
import com.banking.customer.model.Customer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
@Service
public class CustomerService {
    private final Map<String, Customer> store = new ConcurrentHashMap<>();
    public CustomerService() {
        store.put("CUST001", Customer.builder().customerId("CUST001").customerNumber("GB-001")
            .firstName("Priya").lastName("Sharma").email("priya@example.com")
            .phone("+44 7700 900001").status("ACTIVE").linkedAccountIds(List.of("ACC001")).build());
        store.put("CUST002", Customer.builder().customerId("CUST002").customerNumber("GB-002")
            .firstName("Raj").lastName("Patel").email("raj@example.com")
            .phone("+44 7700 900002").status("ACTIVE").linkedAccountIds(List.of("ACC002")).build());
    }
    public Mono<Customer> getByAccountId(String accountId) {
        return Mono.justOrEmpty(store.values().stream()
            .filter(c -> c.getLinkedAccountIds().contains(accountId)).findFirst());
    }
}
