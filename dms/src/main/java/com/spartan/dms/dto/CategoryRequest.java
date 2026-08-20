package com.spartan.dms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryRequest {

    @NotBlank(message = "Category Name is required")
    @Size(min = 2, max = 100, message = "Category Name must be between 2 and 100 characters")
    private String categoryName;

    @Size(max = 255, message = "Description cannot exceed 255 characters")
    private String description;

    private Boolean active;
}