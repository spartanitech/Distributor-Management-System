package com.spartan.dms.repository;

import com.spartan.dms.entity.SalesReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SalesReturnRepository extends JpaRepository<SalesReturn, Long> {

    @Query("SELECT sr FROM SalesReturn sr " +
           "LEFT JOIN FETCH sr.shop LEFT JOIN FETCH sr.distributor " +
           "LEFT JOIN FETCH sr.product LEFT JOIN FETCH sr.invoice " +
           "WHERE sr.distributor.id = :distributorId")
    List<SalesReturn> findByDistributorId(@Param("distributorId") Long distributorId);

    @Query("SELECT sr FROM SalesReturn sr " +
           "LEFT JOIN FETCH sr.shop LEFT JOIN FETCH sr.distributor " +
           "LEFT JOIN FETCH sr.product LEFT JOIN FETCH sr.invoice " +
           "WHERE sr.distributor.superStockist.id = :superStockistId")
    List<SalesReturn> findByDistributorSuperStockistId(@Param("superStockistId") Long superStockistId);

    @Query("SELECT sr FROM SalesReturn sr " +
           "LEFT JOIN FETCH sr.shop LEFT JOIN FETCH sr.distributor " +
           "LEFT JOIN FETCH sr.product LEFT JOIN FETCH sr.invoice")
    List<SalesReturn> findAllWithDetails();

    List<SalesReturn> findByReturnDateBetween(LocalDate from, LocalDate to);

    List<SalesReturn> findByDistributorIdAndReturnDateBetween(Long distributorId, LocalDate from, LocalDate to);

    @Query("SELECT sr FROM SalesReturn sr " +
           "LEFT JOIN FETCH sr.shop LEFT JOIN FETCH sr.distributor " +
           "LEFT JOIN FETCH sr.product LEFT JOIN FETCH sr.invoice " +
           "WHERE sr.shop.id = :shopId ORDER BY sr.returnDate ASC")
    List<SalesReturn> findByShopIdOrderByReturnDateAsc(@Param("shopId") Long shopId);

    @Query("SELECT sr FROM SalesReturn sr " +
           "LEFT JOIN FETCH sr.shop LEFT JOIN FETCH sr.distributor " +
           "LEFT JOIN FETCH sr.product LEFT JOIN FETCH sr.invoice " +
           "ORDER BY sr.returnDate ASC")
    List<SalesReturn> findAllOrderByReturnDateAsc();

    List<SalesReturn> findByInvoiceId(Long invoiceId);

    long countByReturnDateBetween(LocalDate from, LocalDate to);

    @Query("SELECT COALESCE(SUM(sr.returnAmount), 0) FROM SalesReturn sr WHERE sr.returnDate BETWEEN :from AND :to")
    java.math.BigDecimal sumReturnAmountBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
