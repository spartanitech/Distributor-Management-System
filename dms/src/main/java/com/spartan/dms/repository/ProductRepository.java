package com.spartan.dms.repository;

import com.spartan.dms.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByProductNameContainingIgnoreCase(String keyword);

    List<Product> findByCategoryId(Long categoryId);

    // Delete-guard for CategoryService — a category with any products
    // still assigned to it can't be hard-deleted (Product.category is
    // NOT NULL with no cascade), so check first instead of surfacing a
    // raw FK-violation 500.
    boolean existsByCategoryId(Long categoryId);

    Optional<Product> findByProductCode(String productCode);

    Optional<Product> findByBarcode(String barcode);

    boolean existsByProductCode(String productCode);

    /* ---------- Stock health (dashboard KPI + Low Stock widget) ---------- */

    // BUG-L3 fix: minimumStock is a nullable column (legacy/edge-case rows
    // predating ProductService.createProduct()'s default-to-0 write path).
    // SQL NULL comparison semantics mean "stockQuantity <= NULL" evaluates
    // to unknown/false, so a product with a null threshold was silently
    // EXCLUDED from Low Stock instead of being treated as "always low"
    // once it hits zero. COALESCE(p.minimumStock, 0) treats a null
    // threshold as 0, matching what createProduct() already defaults new
    // rows to, so this can never again hide a legacy row with no
    // threshold set.
    @Query("SELECT p FROM Product p JOIN FETCH p.category " +
            "WHERE p.stockQuantity <= COALESCE(p.minimumStock, 0) " +
            "ORDER BY p.stockQuantity ASC")
    List<Product> findLowStockProducts();

    // Same COALESCE fix as findLowStockProducts() above, so the dashboard's
    // Low Stock KPI count always agrees with the widget's actual row count.
    @Query("SELECT COUNT(p) FROM Product p WHERE p.stockQuantity <= COALESCE(p.minimumStock, 0)")
    long countLowStockProducts();

    @Query("SELECT COUNT(p) FROM Product p WHERE p.stockQuantity <= 0")
    long countOutOfStockProducts();
}
