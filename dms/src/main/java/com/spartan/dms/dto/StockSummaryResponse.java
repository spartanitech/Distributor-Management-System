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
public class StockSummaryResponse {

    private LocalDate fromDate;
    private LocalDate toDate;

    private List<StockSummaryRow> rows;

    private BigDecimal grandTotalOpeningValue;
    private BigDecimal grandTotalInwardValue;
    private BigDecimal grandTotalOutwardValue;
    private BigDecimal grandTotalClosingValue;
}
