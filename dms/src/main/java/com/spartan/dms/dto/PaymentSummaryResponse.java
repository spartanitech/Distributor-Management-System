package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSummaryResponse {

    private BigDecimal cashAmount;

    private BigDecimal upiAmount;

    private BigDecimal cardAmount;

    private BigDecimal bankAmount;

    private BigDecimal chequeAmount;

    private BigDecimal totalCollected;
}
