package com.spartan.dms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesReturnRequest {

    @NotNull(message = "Invoice is required")
    private Long invoiceId;

    @NotNull(message = "Product is required")
    private Long productId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    // Optional — defaults to the invoice's own line-item price for this
    // product if not supplied, so the return value always matches what
    // the shop was actually charged.
    @DecimalMin(value = "0", inclusive = true, message = "Unit price cannot be negative")
    private BigDecimal unitPrice;

    private String reason;

    // Required: the caller's OWN current login password, re-verified before
    // this sales return is recorded. Sales returns reverse a sale, adjust
    // stock and the Product Ledger, and are irreversible once past the
    // 25th-of-the-month window -- this confirmation step guards against an
    // unattended/unlocked session being used to record one, the same way a
    // bank re-prompts for a password before an irreversible transaction.
    @jakarta.validation.constraints.NotBlank(message = "Your password is required to confirm this sales return")
    private String confirmPassword;
}
