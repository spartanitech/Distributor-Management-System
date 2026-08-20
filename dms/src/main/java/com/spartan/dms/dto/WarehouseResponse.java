package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseResponse {

    private Long id;
    private String ownerType;

    private Long superStockistId;
    private String superStockistName;

    private Long distributorId;
    private String distributorName;

    private Long productId;
    private String productName;
    private String productCode;

    private Integer quantity;

    // Pricing for the stock this row holds, so a Purchase Return screen can
    // show MRP / the owner's own purchase rate without a second round-trip
    // per product. unitPrice is TIER-CORRECT: the Distributor Price for a
    // distributor's warehouse, the SS Price for a Super Stockist's -- i.e.
    // what THIS owner paid, which is what they get credited on a return.
    private java.math.BigDecimal mrp;
    private java.math.BigDecimal unitPrice;
}
