package com.spartan.dms.repository;

import com.spartan.dms.entity.DistributorAssignment;
import com.spartan.dms.enums.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DistributorAssignmentRepository extends JpaRepository<DistributorAssignment, Long> {

    @Query("SELECT a FROM DistributorAssignment a " +
            "JOIN FETCH a.distributor JOIN FETCH a.shop JOIN FETCH a.product " +
            "ORDER BY a.createdAt DESC")
    List<DistributorAssignment> findAllWithDetails();

    @Query("SELECT a FROM DistributorAssignment a " +
            "JOIN FETCH a.distributor JOIN FETCH a.shop JOIN FETCH a.product " +
            "WHERE a.distributor.id = :distributorId ORDER BY a.createdAt DESC")
    List<DistributorAssignment> findByDistributorId(@Param("distributorId") Long distributorId);

    @Query("SELECT a FROM DistributorAssignment a " +
            "JOIN FETCH a.distributor JOIN FETCH a.shop JOIN FETCH a.product " +
            "WHERE a.status = :status ORDER BY a.createdAt DESC")
    List<DistributorAssignment> findByStatus(@Param("status") AssignmentStatus status);

    @Query("SELECT a FROM DistributorAssignment a " +
            "JOIN FETCH a.distributor JOIN FETCH a.shop JOIN FETCH a.product " +
            "WHERE a.id = :id")
    java.util.Optional<DistributorAssignment> findByIdWithDetails(@Param("id") Long id);

    boolean existsByProductId(Long productId);

    boolean existsByShopId(Long shopId);

    boolean existsByDistributorId(Long distributorId);
}
