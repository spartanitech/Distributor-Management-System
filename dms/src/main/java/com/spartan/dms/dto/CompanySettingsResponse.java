package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanySettingsResponse {

    private Long id;
    private String companyName;
    private String address;
    private String city;
    private String state;
    private String pincode;
    private String gstNumber;
    private String fssaiNumber;
    private String phone;
    private String email;
}
