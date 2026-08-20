package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * One row of the "Outstanding" KPI drill-down: a single shop or distributor
 * that has unpaid/partially-paid balance, with enough context (district,
 * products bought on the pending invoices, invoice count) to act on it —
 * without dumping the full invoice list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutstandingDetailResponse {

    private Long partyId;          // shop id or distributor id
    private String partyType;      // "SHOP" or "DISTRIBUTOR"
    private String partyName;      // shop name or distributor name
    private String ownerOrContact; // owner_name / contact_person
    private String mobileNumber;
    private String district;
    private String state;
    private List<String> products; // distinct product names across their pending invoices
    private Integer pendingInvoiceCount;
    private BigDecimal totalOutstanding;
}
