package com.spartan.dms.mapper;

import com.spartan.dms.dto.CategoryRequest;
import com.spartan.dms.dto.CategoryResponse;
import com.spartan.dms.entity.Category;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class CategoryMapper {

    private final ModelMapper modelMapper;

    public CategoryMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    /**
     * BUG-M1 fix: this is used for partial updates (PUT with only some
     * fields supplied), so unconditionally overwriting a NOT NULL column
     * with a possibly-null request value would silently null it out and
     * surface as a raw DB constraint-violation 500. category_name and
     * active are both NOT NULL at the DB level, so only apply them when
     * the request actually supplied a value — otherwise leave the
     * existing value on the managed entity untouched. description has no
     * such constraint (it's nullable), so it's fine to keep setting it
     * unconditionally, same as before.
     */
    public void updateEntity(CategoryRequest request, Category category) {

        if (request.getCategoryName() != null) {
            category.setCategoryName(request.getCategoryName());
        }
        category.setDescription(request.getDescription());
        if (request.getActive() != null) {
            category.setActive(request.getActive());
        }

    }
    public Category toEntity(CategoryRequest request) {
        return modelMapper.map(request, Category.class);
    }

    public CategoryResponse toResponse(Category category) {
        return modelMapper.map(category, CategoryResponse.class);
    }
}