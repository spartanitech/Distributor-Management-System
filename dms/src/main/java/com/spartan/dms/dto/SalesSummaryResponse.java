package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesSummaryResponse {

    private List<MonthlySalesResponse> monthlySales;

    private List<CategorySalesResponse> categorySales;
}
