package com.banking.payment.model;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Payment {
    private String paymentId, idempotencyKey, sourceAccountId, destinationAccountId;
    private BigDecimal amount;
    private String currency, reference, status, failureReason;
    private String customerName, customerNumber;
    private String httpClientUsed;   // shows "Reactor Netty + WebClient" in response
    private LocalDateTime createdAt, processedAt;
}
