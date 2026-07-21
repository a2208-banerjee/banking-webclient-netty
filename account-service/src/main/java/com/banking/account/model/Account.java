package com.banking.account.model;
import lombok.*;
import java.math.BigDecimal;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Account {
    private String accountId, accountNumber, sortCode, accountHolder, accountType, currency, status;
    private BigDecimal balance;
}
