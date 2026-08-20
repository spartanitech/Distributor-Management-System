package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentInvoiceResponse {

    private Long id;

    private String invoiceNumber;

    private String shopName;

    private String distributorName;

    private LocalDate invoiceDate;

    private BigDecimal totalAmount;

    private BigDecimal paidAmount;

    private BigDecimal balanceAmount;

    private String paymentStatus;

    private String paymentMethod;

    private String watermark;

    private Boolean verified;

    private LocalDateTime createdAt;
}