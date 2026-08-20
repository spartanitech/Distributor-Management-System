package com.spartan.dms.repository;

import com.spartan.dms.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByCategoryNameContainingIgnoreCase(String keyword);

    boolean existsByCategoryName(String categoryName);

    boolean existsByCategoryNameAndIdNot(String categoryName, Long id);
}