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
public class BookEntryResponse {

    private LocalDate date;
    private String voucherType;    // "Sale" or "Rcpt"
    private String voucherNo;
    private Long voucherId;
    private String partyName;
    private String paymentMethod;  // CASH, UPI, BANK, CHEQUE, CARD — null for Sale rows

    private BigDecimal debit;      // Receipt into this book (cash/bank in-hand increases)
    private BigDecimal credit;     // Payment out of this book — always empty today (see note below)

    private BigDecimal runningBalance; // null for Day Book (spans every party/account, no single balance)
}
