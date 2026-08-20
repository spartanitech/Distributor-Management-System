package com.spartan.dms.dto;

import jakarta.validation.constraints.DecimalMin;
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
public class PartnerPriceRequest {

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0", inclusive = true, message = "Price cannot be negative")
    private BigDecimal price;
}
