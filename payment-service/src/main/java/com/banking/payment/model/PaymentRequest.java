package com.banking.payment.model;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class PaymentRequest {
    @NotBlank private String sourceAccountId;
    @NotBlank private String destinationAccountId;
    @NotNull @DecimalMin("0.01") @DecimalMax("50000.00") private BigDecimal amount;
    @NotBlank @Size(min=3,max=3) private String currency;
    private String reference;
}
