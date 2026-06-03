package com.petsitter.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankAccountInfo {
    private String bankCode;
    private String bankBranch;
    private String bankAccount;
    private String bankPayeeName;
}
