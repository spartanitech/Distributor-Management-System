package com.spartan.dms.repository;

import com.spartan.dms.entity.ProductLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read/append only — there is intentionally no update or delete method
 * here beyond what JpaRepository exposes internally to Hibernate. No
 * service in this codebase calls save() on an existing (non-null id)
 * ProductLedger row; ProductLedgerService.record() always builds a fresh
 * transient entity. See ProductLedger for the immutability rationale.
 */
public interface ProductLedgerRepository extends JpaRepository<ProductLedger, Long> {

    // Delete-guard for ProductService.deleteProduct() -- Opening Stock (at
    // product creation) and PATCH .../stock (manual correction) both write
    // a ledger row without touching InvoiceItem/Warehouse/StockMovement/
    // StockTransfer, so those existing blockers alone can miss a product
    // that's still referenced here. product_id is NOT NULL with no cascade.
    boolean existsByProductId(Long productId);

    // Everything for one product, newest first — the "Product Ledger"
    // detail view.
    Page<ProductLedger> findByProductIdOrderByTransactionDateTimeDescIdDesc(Long productId, Pageable pageable);

    // Same, scoped to one location (Super Stockist / Distributor / Shop
    // views, and Admin filtering by warehouse).
    //
    // ownerType is part of the key, not decoration: one transfer writes a
    // COMPANY OUT row that also carries super_stockist_id (recording who
    // received the goods) and a SUPER_STOCKIST IN row. Matching on the id
    // alone returned BOTH, so a Super Stockist's own ledger view listed
    // the company's side of the transaction as if it were theirs.
    Page<ProductLedger> findByProductIdAndOwnerTypeAndSuperStockistIdOrderByTransactionDateTimeDescIdDesc(
            Long productId, com.spartan.dms.enums.OwnerType ownerType, Long superStockistId, Pageable pageable);

    Page<ProductLedger> findByProductIdAndOwnerTypeAndDistributorIdOrderByTransactionDateTimeDescIdDesc(
            Long productId, com.spartan.dms.enums.OwnerType ownerType, Long distributorId, Pageable pageable);

    Page<ProductLedger> findByProductIdAndShopIdOrderByTransactionDateTimeDescIdDesc(
            Long productId, Long shopId, Pageable pageable);

    // Every ledger row for a Super Stockist / Distributor / Shop, across
    // all products — the "my warehouse's ledger" view for non-admin logins.
    Page<ProductLedger> findByOwnerTypeAndSuperStockistIdOrderByTransactionDateTimeDescIdDesc(
            com.spartan.dms.enums.OwnerType ownerType, Long superStockistId, Pageable pageable);

    Page<ProductLedger> findByOwnerTypeAndDistributorIdOrderByTransactionDateTimeDescIdDesc(
            com.spartan.dms.enums.OwnerType ownerType, Long distributorId, Pageable pageable);

    Page<ProductLedger> findByShopIdOrderByTransactionDateTimeDescIdDesc(Long shopId, Pageable pageable);

    // The most recent entry for a product at a given location — used to
    // seed the running balance for the next entry without recomputing a
    // SUM() over full history on every write.
    @Query("SELECT pl FROM ProductLedger pl WHERE pl.product.id = :productId " +
           "AND pl.ownerType = com.spartan.dms.enums.OwnerType.COMPANY " +
           "ORDER BY pl.transactionDateTime DESC, pl.id DESC")
    java.util.List<ProductLedger> findLatestForCompany(@Param("productId") Long productId,
                                                         org.springframework.data.domain.Pageable limit);

    // ownerType is ESSENTIAL here, not redundant. A single transfer writes
    // TWO rows that both carry super_stockist_id: the COMPANY-side OUT
    // (which records who the goods went TO) and the SUPER_STOCKIST-side
    // IN. Without the ownerType filter this picked up the COMPANY row as
    // the Super Stockist's "previous balance", so their running balance
    // was seeded from the company's figure -- e.g. company -1 then
    // -1 + 1 = 0 instead of the correct 1.
    @Query("SELECT pl FROM ProductLedger pl WHERE pl.product.id = :productId " +
           "AND pl.ownerType = com.spartan.dms.enums.OwnerType.SUPER_STOCKIST " +
           "AND pl.superStockist.id = :superStockistId " +
           "ORDER BY pl.transactionDateTime DESC, pl.id DESC")
    java.util.List<ProductLedger> findLatestForSuperStockist(@Param("productId") Long productId,
                                                               @Param("superStockistId") Long superStockistId,
                                                               org.springframework.data.domain.Pageable limit);

    // Same reasoning as findLatestForSuperStockist above: an
    // SS -> Distributor transfer writes a SUPER_STOCKIST OUT row that also
    // carries distributor_id, which would otherwise be mistaken for the
    // distributor's own previous balance.
    @Query("SELECT pl FROM ProductLedger pl WHERE pl.product.id = :productId " +
           "AND pl.ownerType = com.spartan.dms.enums.OwnerType.DISTRIBUTOR " +
           "AND pl.distributor.id = :distributorId " +
           "ORDER BY pl.transactionDateTime DESC, pl.id DESC")
    java.util.List<ProductLedger> findLatestForDistributor(@Param("productId") Long productId,
                                                             @Param("distributorId") Long distributorId,
                                                             org.springframework.data.domain.Pageable limit);

    @Query("SELECT pl FROM ProductLedger pl WHERE pl.product.id = :productId " +
           "AND pl.shop.id = :shopId " +
           "ORDER BY pl.transactionDateTime DESC, pl.id DESC")
    java.util.List<ProductLedger> findLatestForShop(@Param("productId") Long productId,
                                                      @Param("shopId") Long shopId,
                                                      org.springframework.data.domain.Pageable limit);
}
