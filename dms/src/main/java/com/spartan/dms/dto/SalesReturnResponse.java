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
public class SalesReturnResponse {
    private Long id;
    private Long invoiceId;
    private String invoiceNumber;
    private Long shopId;
    private String shopName;
    private Long distributorId;
    private String distributorName;
    private Long productId;
    private String productName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal returnAmount;
    private LocalDate returnDate;
    private String reason;
    private String status;
}
