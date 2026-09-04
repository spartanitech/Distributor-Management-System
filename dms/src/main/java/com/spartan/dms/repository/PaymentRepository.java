package com.spartan.dms.repository;

import com.spartan.dms.dto.PaymentMethodTotal;
import com.spartan.dms.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByInvoiceId(Long invoiceId);

    List<Payment> findByDistributorId(Long distributorId);

    List<Payment> findByShopId(Long shopId);

    // Payments made directly against a Super Stockist (both as payer on a
    // COMPANY_TO_SUPER_STOCKIST invoice and as recipient/party on a
    // SUPER_STOCKIST_TO_DISTRIBUTOR invoice — superStockist is set on the
    // Payment row itself in both cases, see Payment.superStockist).
    List<Payment> findBySuperStockistId(Long superStockistId);

    @Query("SELECT p FROM Payment p WHERE p.shop.id = :shopId ORDER BY p.paymentDate ASC, p.id ASC")
    List<Payment> findByShopIdOrderByPaymentDateAsc(@org.springframework.data.repository.query.Param("shopId") Long shopId);

    @Query("SELECT p FROM Payment p WHERE p.distributor.id = :distributorId ORDER BY p.paymentDate ASC, p.id ASC")
    List<Payment> findByDistributorIdOrderByPaymentDateAsc(@org.springframework.data.repository.query.Param("distributorId") Long distributorId);

    List<Payment> findByPaymentStatus(String paymentStatus);

    // The "Payment History" list each role sees: strictly the payments that
    // role personally recorded — mirrors InvoiceRepository.findByCreatedByUserId
    // / InvoiceService.scopedInvoices() exactly, so Admin/SS/Distributor
    // visibility rules are consistent between invoices and payments.
    List<Payment> findByCreatedByUserId(Long createdByUserId);

    // Cash Book / Bank Book / Day Book — every payment with shop/distributor
    // eagerly fetched so the service can show the party name without N+1
    // queries. inMethods is null-safe checked by the service (Spring Data
    // doesn't support an "IN with null = match all" shortcut cleanly, so
    // the service picks one of the two queries below based on bookType).
    @Query("SELECT p FROM Payment p LEFT JOIN FETCH p.shop LEFT JOIN FETCH p.distributor " +
            "WHERE p.paymentMethod IN :methods ORDER BY p.paymentDate ASC, p.id ASC")
    List<Payment> findByPaymentMethodInOrderByPaymentDateAsc(@org.springframework.data.repository.query.Param("methods") List<String> methods);

    @Query("SELECT p FROM Payment p LEFT JOIN FETCH p.shop LEFT JOIN FETCH p.distributor ORDER BY p.paymentDate ASC, p.id ASC")
    List<Payment> findAllOrderByPaymentDateAsc();

    /* ---------- Payment summary (grouped by method, bucketed in service) ---------- */

    @Query("SELECT new com.spartan.dms.dto.PaymentMethodTotal(p.paymentMethod, SUM(p.amount)) " +
            "FROM Payment p GROUP BY p.paymentMethod")
    List<PaymentMethodTotal> sumAmountGroupedByMethod();

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p")
    BigDecimal sumAllAmount();
}
