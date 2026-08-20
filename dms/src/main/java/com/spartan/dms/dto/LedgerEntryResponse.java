package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntryResponse {

    private LocalDate date;
    private String voucherType;   // "Sale" or "Rcpt" — matches Busy/Tally labels
    private String voucherNo;     // invoice number, or "PAY-<id>" for a receipt
    private Long voucherId;       // invoice id or payment id — for the drill-down click
    private String narration;

    private BigDecimal debit;     // Sale increases what the party owes
    private BigDecimal credit;    // Receipt reduces what the party owes

    private BigDecimal runningBalance;
    private String runningBalanceType; // "Dr" or "Cr"
}
