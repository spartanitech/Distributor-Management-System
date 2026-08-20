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
public class DistributorResponse {

    private Long id;

    private String distributorName;

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

    private Long superStockistId;

    private String superStockistName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}