package com.spartan.dms.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRequest {

    @NotBlank(message = "Full Name is required")
    private String fullName;

    @NotBlank(message = "Username is required")
    private String username;

    @Email(message = "Invalid Email")
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Mobile Number is required")
    @Pattern(regexp = "^[6-9]\\d{9}$", message = "Invalid Mobile Number")
    private String mobileNumber;

    @NotBlank(message = "Password is required")
    private String password;

    // Self-service password changes only (UserService.updateProfile) — the
    // caller must prove they know the account's current password before a
    // new one is accepted. Not required for Admin creating/editing another
    // user's account (createUser/updateUser), where Admin's own auth is
    // already the authorization for that.
    private String currentPassword;

    @NotNull(message = "Role is required")
    private Long roleId;

    // Required when roleId refers to the DISTRIBUTOR role; the account
    // created will be scoped to this distributor. Ignored for ADMIN users.
    private Long distributorId;

    // Required when roleId refers to the SUPER_STOCKIST role; the account
    // created will be scoped to this Super Stockist. Ignored otherwise.
    // Without this, UserService had no way to link a SUPER_STOCKIST-role
    // account created here to an actual SuperStockist record -- the
    // account would log in fine but get a 403 on every scoped action,
    // since SecurityUtils.getScopedSuperStockistId() requires the link.
    private Long superStockistId;

    private Boolean active;
}