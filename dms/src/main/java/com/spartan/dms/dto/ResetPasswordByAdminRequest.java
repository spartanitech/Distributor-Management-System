package com.spartan.dms.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// BUG-H10 fix: carries the admin-set new password for
// UserController.resetPassword() in the request BODY instead of a
// ?password= query string (query params end up in server access logs,
// browser history, and proxy logs — never an acceptable place for a
// secret). Deliberately separate from UserRequest (which is a much
// bigger, multi-purpose DTO) to keep this endpoint's contract minimal.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordByAdminRequest {

    @NotBlank(message = "Password is required")
    private String password;
}
