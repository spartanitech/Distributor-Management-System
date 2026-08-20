package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * "MRP-wise stock" — current on-hand quantity grouped by each distinct MRP
 * price point in the catalog (products are single-MRP in this schema, so
 * this groups whole products by their MRP rather than tracking multiple
 * MRP batches of the same product). Scope depends on caller (see
 * ProductService.getMrpWiseStock()): Admin sees the Company root stock
 * count (Product.stockQuantity); a Super Stockist/Distributor sees their
 * own on-hand Warehouse stock only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MrpWiseStockResponse {

    private List<Group> groups;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Group {
        private BigDecimal mrp;
        private Integer totalQuantity;
        private List<ProductLine> products;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductLine {
        private Long productId;
        private String productName;
        private String productCode;
        private Integer quantity;
    }
}
