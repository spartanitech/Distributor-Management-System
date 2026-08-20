package com.spartan.dms.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "Full Name is required")
    private String fullName;

    @NotBlank(message = "Username is required")
    private String username;

    @Email(message = "Invalid Email")
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Phone number is required")
    private String phone;

    @NotBlank(message = "Password is required")
    @jakarta.validation.constraints.Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    // Which portal this self-registration is for: "DISTRIBUTOR" or
    // "SUPER_STOCKIST". Optional — defaults to DISTRIBUTOR to preserve the
    // existing public sign-up form's behavior. See AuthService.register.
    private String requestedRole;
}