package com.spartan.dms.repository;

import com.spartan.dms.entity.Distributor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface DistributorRepository extends JpaRepository<Distributor, Long> {

    List<Distributor> findByDistributorNameContainingIgnoreCase(String keyword);

    boolean existsByMobileNumber(String mobileNumber);

    // Used by UserService when auto-creating the linked profile for a
    // new Distributor login: if a record with this mobile already
    // exists, reuse it instead of creating a duplicate (mobile_number
    // is unique, so a blind insert would fail anyway).
    java.util.Optional<Distributor> findByMobileNumber(String mobileNumber);

    boolean existsByEmail(String email);

    boolean existsByGstNumber(String gstNumber);

    // Used by updateDistributor() so editing a record without changing its
    // own mobile/email/gst never falsely flags itself as a duplicate.
    boolean existsByMobileNumberAndIdNot(String mobileNumber, Long id);

    boolean existsByEmailAndIdNot(String email, Long id);

    boolean existsByGstNumberAndIdNot(String gstNumber, Long id);

    /* ---------- Super Stockist scoping ---------- */
    // Used by DistributorService/SecurityUtils so a Super Stockist login
    // only ever sees/manages distributors assigned to them, no matter how
    // many super stockists or distributors exist.

    List<Distributor> findBySuperStockistId(Long superStockistId);

    boolean existsByIdAndSuperStockistId(Long id, Long superStockistId);

    long countBySuperStockistId(Long superStockistId);

    // Batch counterpart of countBySuperStockistId. Calling that per row
    // while listing Super Stockists is an N+1 (one query for the list plus
    // one COUNT per row); this returns every count in a single grouped
    // query. Super Stockists with no distributors are simply absent from
    // the result -- callers default those to 0.
    @Query("SELECT d.superStockist.id, COUNT(d) FROM Distributor d WHERE d.superStockist IS NOT NULL GROUP BY d.superStockist.id")
    List<Object[]> countGroupedBySuperStockist();

    // Legacy/data-migration finder: rows with no Super Stockist link at all.
    // The service layer requires superStockist on every new/updated
    // Distributor (see DistributorService), so any row this returns
    // predates that rule and needs an admin to assign it — every
    // hierarchy-scoped query and report treats these as "Unassigned"
    // rather than failing, but they should still be found and fixed.
    List<Distributor> findBySuperStockistIsNull();

    /* ---------- Growth trend (dashboard KPI) ---------- */

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
