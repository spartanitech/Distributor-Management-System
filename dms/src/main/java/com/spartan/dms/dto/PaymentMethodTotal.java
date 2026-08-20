package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Internal projection used by PaymentRepository to group total payment
 * amounts by payment method before DashboardService buckets them into
 * PaymentSummaryResponse (cash / upi / card / bank / cheque).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodTotal {

    private String paymentMethod;

    private BigDecimal totalAmount;
}
