package com.spartan.dms.repository;

import com.spartan.dms.entity.ProductDistributorPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductDistributorPriceRepository extends JpaRepository<ProductDistributorPrice, Long> {

    Optional<ProductDistributorPrice> findByProductIdAndDistributorId(Long productId, Long distributorId);

    List<ProductDistributorPrice> findByProductId(Long productId);

    boolean existsByProductId(Long productId);

    List<ProductDistributorPrice> findByDistributorId(Long distributorId);

    boolean existsByDistributorId(Long distributorId);

    void deleteByProductIdAndDistributorId(Long productId, Long distributorId);
}
