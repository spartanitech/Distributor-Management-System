package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountLedgerResponse {

    private String partyType;   // SHOP or DISTRIBUTOR
    private Long partyId;
    private String partyName;
    private String gstNumber;

    private LocalDate fromDate;
    private LocalDate toDate;

    private BigDecimal openingBalance;
    private String openingBalanceType; // Dr or Cr

    private List<LedgerEntryResponse> entries;

    private BigDecimal totalDebit;
    private BigDecimal totalCredit;

    private BigDecimal closingBalance;
    private String closingBalanceType; // Dr or Cr
}
