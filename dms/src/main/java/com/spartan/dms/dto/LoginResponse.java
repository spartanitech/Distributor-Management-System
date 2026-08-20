package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private Long userId;

    private String fullName;

    private String username;

    private String email;

    private String role;

    private Long distributorId;

    private String distributorName;

    private Long superStockistId;

    private String superStockistName;

    private String token;

    private String refreshToken;

    private String themePreference;
    private String fontSizePreference;
    private String profileImage;
}