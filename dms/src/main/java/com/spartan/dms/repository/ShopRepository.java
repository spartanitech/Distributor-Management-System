package com.spartan.dms.repository;

import com.spartan.dms.entity.Shop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShopRepository extends JpaRepository<Shop, Long> {

    List<Shop> findByShopNameContainingIgnoreCase(String keyword);

    List<Shop> findByDistributorId(Long distributorId);

    boolean existsByMobileNumber(String mobileNumber);

    boolean existsByEmail(String email);

    boolean existsByGstNumber(String gstNumber);

    boolean existsByMobileNumberAndIdNot(String mobileNumber, Long id);

    boolean existsByEmailAndIdNot(String email, Long id);

    boolean existsByGstNumberAndIdNot(String gstNumber, Long id);

    // A Super Stockist's shops: every shop belonging to any distributor assigned to them.
    List<Shop> findByDistributor_SuperStockist_Id(Long superStockistId);
}