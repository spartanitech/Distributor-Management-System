package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettingsRequest {

    // "light" or "dark"
    private String themePreference;

    // "small", "medium", or "large"
    private String fontSizePreference;

    // Data URL / hosted URL for the profile photo. Kept as a plain string
    // (same pattern as Product.productImage) rather than a file upload
    // endpoint, to stay consistent with how images are already handled
    // elsewhere in this codebase.
    private String profileImage;
}
