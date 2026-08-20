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
public class ShopResponse {

    private Long id;

    private String shopName;

    private String ownerName;

    private String mobileNumber;

    private String email;

    private String address;

    private String city;

    private String state;

    private String district;

    private String pincode;

    private String gstNumber;

    private Long distributorId;

    private String distributorName;

    private Boolean active;

    private String remarks;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}