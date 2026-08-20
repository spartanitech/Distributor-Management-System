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
public class BookResponse {

    private String bookType; // CASH, BANK, or DAY
    private LocalDate fromDate;
    private LocalDate toDate;

    private BigDecimal openingBalance; // null for Day Book
    private List<BookEntryResponse> entries;
    private BigDecimal totalDebit;
    private BigDecimal totalCredit;
    private BigDecimal closingBalance; // null for Day Book

    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
