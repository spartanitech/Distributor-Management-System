package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuperStockistResponse {

    private Long id;

    private String superStockistName;

    private String contactPerson;

    private String mobileNumber;

    private String email;

    private String address;

    private String city;

    private String state;

    private String district;

    private String pincode;

    private String gstNumber;

    private String licenseNumber;

    private Boolean active;

    // Count of distributors currently assigned to this Super Stockist.
    // Populated by SuperStockistService, not by the ModelMapper pass-through,
    // since it's a derived aggregate rather than a column on the entity.
    private long assignedDistributorCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
