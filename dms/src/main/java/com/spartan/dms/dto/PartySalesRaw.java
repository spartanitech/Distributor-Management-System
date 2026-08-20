package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Internal-only projection used by SalesAnalysisRepository's JPQL
 * constructor expressions. It's identical for Super Stockist / Distributor
 * / Shop grouping — SalesAnalysisService is what stamps the correct
 * partyType onto the final PartySalesResponse, since JPQL constructor
 * expressions can't cleanly embed a literal string alongside aggregates
 * across three differently-grouped queries.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartySalesRaw {

    private Long partyId;
    private String partyName;
    private Long invoiceCount;
    private BigDecimal totalSales;
}
