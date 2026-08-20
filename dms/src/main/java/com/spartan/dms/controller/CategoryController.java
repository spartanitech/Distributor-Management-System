package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.CategoryRequest;
import com.spartan.dms.dto.CategoryResponse;
import com.spartan.dms.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CategoryController {

    private final CategoryService categoryService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @RequestBody CategoryRequest request) {

        return ResponseEntity.ok(categoryService.createCategory(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @PathVariable Long id,
            @RequestBody CategoryRequest request) {

        return ResponseEntity.ok(categoryService.updateCategory(id, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteCategory(
            @PathVariable Long id) {

        return ResponseEntity.ok(categoryService.deleteCategory(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> getCategoryById(
            @PathVariable Long id) {

        return ResponseEntity.ok(categoryService.getCategoryById(id));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getAllCategories() {

        return ResponseEntity.ok(categoryService.getAllCategories());
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> searchCategory(
            @RequestParam String keyword) {

        return ResponseEntity.ok(categoryService.searchCategory(keyword));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<String>> updateCategoryStatus(
            @PathVariable Long id,
            @RequestParam Boolean status) {

        return ResponseEntity.ok(categoryService.updateCategoryStatus(id, status));
    }
}