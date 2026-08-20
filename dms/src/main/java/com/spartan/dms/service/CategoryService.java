package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.CategoryRequest;
import com.spartan.dms.dto.CategoryResponse;
import com.spartan.dms.entity.Category;
import com.spartan.dms.exception.DuplicateResourceException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.mapper.CategoryMapper;
import com.spartan.dms.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;
    private final com.spartan.dms.repository.ProductRepository productRepository;
    private final AuditLogService auditLogService;

    public ApiResponse<CategoryResponse> createCategory(CategoryRequest request) {

        if (categoryRepository.existsByCategoryName(request.getCategoryName())) {
            throw new DuplicateResourceException("Category already exists");
        }

        Category category = categoryMapper.toEntity(request);
        category = categoryRepository.save(category);

        auditLogService.log("CREATE", "CATEGORY", category.getId(),
                "Created category " + category.getCategoryName());

        return ApiResponse.<CategoryResponse>builder()
                .success(true)
                .message("Category Created Successfully")
                .data(categoryMapper.toResponse(category))
                .build();
    }

    public ApiResponse<List<CategoryResponse>> getAllCategories() {

        List<CategoryResponse> categories = categoryRepository.findAll()
                .stream()
                .map(categoryMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<CategoryResponse>>builder()
                .success(true)
                .message("Category List")
                .data(categories)
                .build();
    }

    public ApiResponse<CategoryResponse> getCategoryById(Long id) {

        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        return ApiResponse.<CategoryResponse>builder()
                .success(true)
                .message("Category Details")
                .data(categoryMapper.toResponse(category))
                .build();
    }

    public ApiResponse<CategoryResponse> updateCategory(Long id, CategoryRequest request) {

        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        // Same uniqueness rule createCategory() enforces, but excluding this
        // category's own row -- without the IdNot check, saving a category
        // without touching its name would false-positive against itself,
        // and renaming it to collide with a DIFFERENT existing category
        // would previously fall through to a raw DB unique-constraint
        // violation (categoryName has a unique index) instead of a clean
        // error message.
        if (request.getCategoryName() != null
                && categoryRepository.existsByCategoryNameAndIdNot(request.getCategoryName(), id)) {
            throw new DuplicateResourceException("Category already exists");
        }

        categoryMapper.updateEntity(request, category);

        category = categoryRepository.save(category);

        auditLogService.log("UPDATE", "CATEGORY", category.getId(),
                "Updated category " + category.getCategoryName());

        return ApiResponse.<CategoryResponse>builder()
                .success(true)
                .message("Category Updated Successfully")
                .data(categoryMapper.toResponse(category))
                .build();
    }

    public ApiResponse<String> deleteCategory(Long id) {

        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        // A category with any products still assigned can't be hard-deleted
        // -- Product.category is NOT NULL with no cascade, so this would
        // otherwise surface as a raw FK-violation 500 instead of a clean,
        // actionable message. Mirrors the same blockers pattern
        // ProductService/DistributorService/SuperStockistService already
        // use for their own delete guards.
        if (productRepository.existsByCategoryId(id)) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "Cannot delete: this category still has products assigned to it. "
                            + "Reassign or remove those products first, or set this category to Inactive instead.");
        }

        categoryRepository.delete(category);

        auditLogService.log("DELETE", "CATEGORY", id, "Deleted category " + category.getCategoryName());

        return ApiResponse.<String>builder()
                .success(true)
                .message("Category Deleted Successfully")
                .data("Deleted")
                .build();
    }
    public ApiResponse<List<CategoryResponse>> searchCategory(String keyword) {

        List<CategoryResponse> categories = categoryRepository.findAll()
                .stream()
                .filter(category ->
                        category.getCategoryName() != null &&
                                category.getCategoryName().toLowerCase()
                                        .contains(keyword.toLowerCase()))
                .map(categoryMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<CategoryResponse>>builder()
                .success(true)
                .message("Category Search Result")
                .data(categories)
                .build();
    }

    public ApiResponse<String> updateCategoryStatus(Long id, Boolean status) {

        Category category = categoryRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Category not found"));

        category.setActive(status);

        categoryRepository.save(category);

        auditLogService.log("STATUS_CHANGE", "CATEGORY", id,
                "Category " + category.getCategoryName() + " status set to " + status);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Category Status Updated Successfully")
                .data("Success")
                .build();
    }
}