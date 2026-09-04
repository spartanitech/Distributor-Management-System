package com.spartan.dms.config;

import com.spartan.dms.entity.Payment;
import com.spartan.dms.entity.User;
import com.spartan.dms.repository.PaymentRepository;
import com.spartan.dms.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-time data backfill for Payment.createdByUserId.
 *
 * That field didn't exist until this fix (see PaymentService.stampCreator()
 * and PaymentService.getAllPayments(), which now scopes each role's
 * "Payment History" list strictly to payments THEY personally recorded —
 * matching InvoiceService.scopedInvoices()'s Admin-sees-all /
 * everyone-else-sees-only-their-own rule, which previously only applied to
 * invoices, not payments). Every payment recorded before this change has
 * createdByUserId = null, so it would otherwise silently vanish from that
 * SS/Distributor's own Payment History (Admin still sees it via findAll()).
 *
 * This runner fills in the gap: for each payment still missing a creator,
 * it looks up the one login account scoped to that payment's distributor
 * (or, if none, its super stockist) and attributes the payment to them —
 * the accurate answer in the overwhelmingly common case of one login per
 * distributor/super stockist. Runs once per boot, only ever touches rows
 * where createdByUserId IS NULL, so it's a no-op once everything is
 * backfilled (and for any payment recorded after this fix, which already
 * has a creator from the moment it's created).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PaymentCreatorBackfillRunner implements CommandLineRunner {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;

    public PaymentCreatorBackfillRunner(PaymentRepository paymentRepository, UserRepository userRepository) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        try {
            List<Payment> unattributed = paymentRepository.findAll().stream()
                    .filter(p -> p.getCreatedByUserId() == null)
                    .toList();

            if (unattributed.isEmpty()) {
                return;
            }

            int backfilled = 0;
            for (Payment payment : unattributed) {
                User owner = resolveOwner(payment);
                if (owner == null) {
                    continue; // no matching login found -- leave unattributed, admin still sees it
                }
                payment.setCreatedByUserId(owner.getId());
                payment.setCreatedByUsername(owner.getUsername());
                payment.setCreatedByRole(owner.getRole() != null ? owner.getRole().getRoleName() : null);
                backfilled++;
            }

            if (backfilled > 0) {
                paymentRepository.saveAll(unattributed);
                log.warn("Payment creator backfill: attributed {} pre-existing payment(s) to the login " +
                        "account scoped to their distributor/super stockist (one-time fix, see " +
                        "PaymentCreatorBackfillRunner for why).", backfilled);
            }
        } catch (Exception e) {
            // Never block application startup over this -- worst case, a
            // handful of old payments stay visible to Admin only until this
            // is looked at, which is exactly where they still were before.
            log.error("Payment creator backfill failed -- some pre-existing payments may not appear " +
                    "in their SS/Distributor's own Payment History until this is investigated.", e);
        }
    }

    private User resolveOwner(Payment payment) {
        if (payment.getDistributor() != null) {
            List<User> users = userRepository.findByDistributorId(payment.getDistributor().getId());
            if (!users.isEmpty()) {
                return users.get(0);
            }
        }
        if (payment.getSuperStockist() != null) {
            List<User> users = userRepository.findBySuperStockistId(payment.getSuperStockist().getId());
            if (!users.isEmpty()) {
                return users.get(0);
            }
        }
        return null;
    }
}
