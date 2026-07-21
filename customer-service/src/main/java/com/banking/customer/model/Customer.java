package com.banking.customer.model;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Customer {
    private String customerId, customerNumber, firstName, lastName, email, phone, status;
    private List<String> linkedAccountIds;
    public String getFullName() { return firstName + " " + lastName; }
}
