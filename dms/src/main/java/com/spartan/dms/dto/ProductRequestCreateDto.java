package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductRequestCreateDto {

    // Ignored (and overwritten with the caller's own distributor) when the
    // request is submitted by a DISTRIBUTOR login; only an ADMIN raising a
    // request on a distributor's behalf needs to set this explicitly.
    private Long distributorId;

    // Set this (and leave distributorId null) to raise a Super Stockist ->
    // Company request instead. Ignored (and overwritten with the caller's
    // own Super Stockist) when submitted by a SUPER_STOCKIST login.
    private Long superStockistId;

    private Long productId;

    private Integer requestedQuantity;

    private String remarks;
}
