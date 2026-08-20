package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {

    private List<T> content;
    private int page;       // 0-based
    private int size;
    private long totalElements;
    private int totalPages;
    private BigDecimalTotals totals; // optional grand-total row, null if not applicable

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BigDecimalTotals {
        private java.math.BigDecimal grandTotalOutstanding;
        private int grandTotalPendingInvoices;
    }
}
