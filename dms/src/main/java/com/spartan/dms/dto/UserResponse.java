package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private Long id;

    private String fullName;

    private String username;

    private String email;

    private String mobileNumber;

    private String role;

    private Long distributorId;

    private String distributorName;

    private Long superStockistId;

    private String superStockistName;

    private Boolean active;

    private String approvalStatus;

    private java.time.LocalDateTime createdAt;

    private String themePreference;
    private String fontSizePreference;
    private String profileImage;
}