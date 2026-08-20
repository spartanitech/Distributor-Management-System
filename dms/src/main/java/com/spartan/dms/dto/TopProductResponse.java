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
public class TopProductResponse {

    private String productName;

    private String categoryName;

    private Long quantitySold;

    private BigDecimal totalRevenue;
}
