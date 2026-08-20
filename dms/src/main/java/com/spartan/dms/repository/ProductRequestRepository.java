package com.spartan.dms.repository;

import com.spartan.dms.entity.ProductRequest;
import com.spartan.dms.enums.ProductRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRequestRepository extends JpaRepository<ProductRequest, Long> {

    @Query("SELECT pr FROM ProductRequest pr " +
            "JOIN FETCH pr.distributor JOIN FETCH pr.product " +
            "WHERE pr.distributor.id = :distributorId " +
            "ORDER BY pr.createdAt DESC")
    List<ProductRequest> findByDistributorId(@Param("distributorId") Long distributorId);

    @Query("SELECT pr FROM ProductRequest pr " +
            "LEFT JOIN FETCH pr.distributor LEFT JOIN FETCH pr.superStockist JOIN FETCH pr.product " +
            "WHERE pr.superStockist.id = :superStockistId " +
            "ORDER BY pr.createdAt DESC")
    List<ProductRequest> findBySuperStockistId(@Param("superStockistId") Long superStockistId);

    @Query("SELECT pr FROM ProductRequest pr " +
            "LEFT JOIN FETCH pr.distributor LEFT JOIN FETCH pr.superStockist JOIN FETCH pr.product " +
            "WHERE pr.superStockist.id = :superStockistId AND pr.requestLevel = :level " +
            "ORDER BY pr.createdAt DESC")
    List<ProductRequest> findBySuperStockistIdAndRequestLevel(
            @Param("superStockistId") Long superStockistId,
            @Param("level") com.spartan.dms.enums.RequestLevel level);

    @Query("SELECT pr FROM ProductRequest pr " +
            "LEFT JOIN FETCH pr.distributor LEFT JOIN FETCH pr.superStockist JOIN FETCH pr.product " +
            "WHERE pr.requestLevel = :level " +
            "ORDER BY pr.createdAt DESC")
    List<ProductRequest> findByRequestLevel(@Param("level") com.spartan.dms.enums.RequestLevel level);

    @Query("SELECT pr FROM ProductRequest pr " +
            "LEFT JOIN FETCH pr.distributor LEFT JOIN FETCH pr.superStockist JOIN FETCH pr.product " +
            "ORDER BY pr.createdAt DESC")
    List<ProductRequest> findAllWithDetails();

    @Query("SELECT pr FROM ProductRequest pr " +
            "LEFT JOIN FETCH pr.distributor LEFT JOIN FETCH pr.superStockist JOIN FETCH pr.product " +
            "WHERE pr.status = :status ORDER BY pr.createdAt DESC")
    List<ProductRequest> findByStatus(@Param("status") ProductRequestStatus status);

    @Query("SELECT pr FROM ProductRequest pr " +
            "LEFT JOIN FETCH pr.distributor LEFT JOIN FETCH pr.superStockist JOIN FETCH pr.product " +
            "WHERE pr.id = :id")
    java.util.Optional<ProductRequest> findByIdWithDetails(@Param("id") Long id);

    long countByStatus(ProductRequestStatus status);

    boolean existsByProductId(Long productId);

    boolean existsByDistributorId(Long distributorId);
}
