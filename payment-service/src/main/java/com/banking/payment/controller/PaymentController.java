package com.banking.payment.controller;
import com.banking.payment.model.*;
import com.banking.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;
    @PostMapping
    public Mono<ResponseEntity<Payment>> processPayment(@Valid @RequestBody PaymentRequest request) {
        return paymentService.processPayment(request).map(p ->
            ResponseEntity.status("COMPLETED".equals(p.getStatus()) ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY).body(p));
    }
    @GetMapping
    public Flux<Payment> getAllPayments() { return paymentService.getAllPayments(); }
    @GetMapping("/{id}")
    public Mono<ResponseEntity<Payment>> getById(@PathVariable String id) {
        return paymentService.getPaymentById(id).map(ResponseEntity::ok).defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
