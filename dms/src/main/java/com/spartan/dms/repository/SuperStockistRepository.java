package com.spartan.dms.repository;

import com.spartan.dms.entity.SuperStockist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SuperStockistRepository extends JpaRepository<SuperStockist, Long> {

    List<SuperStockist> findBySuperStockistNameContainingIgnoreCase(String keyword);

    boolean existsByMobileNumber(String mobileNumber);

    // Used by UserService when auto-creating the linked profile for a
    // new SuperStockist login: if a record with this mobile already
    // exists, reuse it instead of creating a duplicate (mobile_number
    // is unique, so a blind insert would fail anyway).
    java.util.Optional<SuperStockist> findByMobileNumber(String mobileNumber);

    boolean existsByEmail(String email);

    boolean existsByGstNumber(String gstNumber);

    boolean existsByMobileNumberAndIdNot(String mobileNumber, Long id);

    boolean existsByEmailAndIdNot(String email, Long id);

    boolean existsByGstNumberAndIdNot(String gstNumber, Long id);

    long countByActiveTrue();

    /* ---------- Growth trend (dashboard KPI) ---------- */

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
