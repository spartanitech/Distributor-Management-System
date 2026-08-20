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
public class CategoryResponse {

    private Long id;

    private String categoryName;

    private String description;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}