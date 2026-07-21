package com.banking.payment.model;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonIgnoreProperties(ignoreUnknown=true)
public class CustomerDto {
    private String customerId, fullName, email, phone, customerNumber, status;
}
