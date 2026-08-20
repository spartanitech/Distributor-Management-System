package com.spartan.dms.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopRequest {

    @NotBlank(message = "Shop Name is required")
    private String shopName;

    @NotBlank(message = "Owner Name is required")
    private String ownerName;

    @NotBlank(message = "Mobile Number is required")
    @Pattern(regexp = "^[6-9]\\d{9}$", message = "Invalid Mobile Number")
    private String mobileNumber;

    @Email(message = "Invalid Email")
    private String email;

    private String address;

    private String city;

    private String state;

    private String district;

    private String pincode;

    @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$", message = "Invalid GST Number (must be a valid 15-character GSTIN)")
    private String gstNumber;

    private Long distributorId;

    private Boolean active;

    private String remarks;
}