package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.PaymentProofResponse;
import com.spartan.dms.dto.PaymentRequest;
import com.spartan.dms.dto.PaymentResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.Invoice;
import com.spartan.dms.entity.Payment;
import com.spartan.dms.entity.PaymentProof;
import com.spartan.dms.entity.Shop;
import com.spartan.dms.exception.ForbiddenException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.mapper.PaymentMapper;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.InvoiceRepository;
import com.spartan.dms.repository.PaymentRepository;
import com.spartan.dms.repository.ShopRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final InvoiceRepository invoiceRepository;
    private final DistributorRepository distributorRepository;
    private final ShopRepository shopRepository;
    private final SecurityUtils securityUtils;

    // Injected so PaymentController's /payments/{id}/upload-proof endpoint
    // can delegate to the single, real upload implementation instead of
    // duplicating (and diverging from) the file-handling logic. See
    // uploadPaymentProof() below.
    private final PaymentProofService paymentProofService;
    private final com.spartan.dms.repository.PaymentProofRepository paymentProofRepository;
    private final com.spartan.dms.repository.SuperStockistRepository superStockistRepository;
    private final AuditLogService auditLogService;
    private final com.spartan.dms.util.PdfGenerator pdfGenerator;

    // ---- BUG-C2 fix ----
    // Every method below that mutates both a Payment row and its Invoice's
    // paidAmount/balanceAmount/paymentStatus is now @Transactional: the
    // Payment save and the Invoice save either both commit or both roll
    // back together, closing the "payment saved but invoice never
    // updated" partial-failure gap. Concurrency (two payments racing on
    // the same invoice) is closed by Invoice.version (@Version, added in
    // BUG-C2's entity change): both transactions read the same starting
    // paidAmount, but only the FIRST to commit succeeds — the second's
    // invoiceRepository.save(invoice) throws
    // ObjectOptimisticLockingFailureException (already mapped to a clean
    // HTTP 409 by GlobalExceptionHandler) instead of silently overwriting
    // the first payment's effect on the running total.
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<PaymentResponse> createPayment(PaymentRequest request) {

        Invoice invoice = invoiceRepository.findById(request.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));

        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new com.spartan.dms.exception.BadRequestException("Payment amount must be greater than zero");
        }
        // BUG-H6 fix: paymentMethod is NOT NULL at the DB level but was
        // never validated before hitting the database, so omitting it used
        // to surface as a raw constraint-violation conflict instead of a
        // clean, actionable 400.
        if (request.getPaymentMethod() == null || request.getPaymentMethod().isBlank()) {
            throw new com.spartan.dms.exception.BadRequestException("paymentMethod is required");
        }

        // BUG-H7 fix: totalAmount is guaranteed non-null for every invoice
        // created after the BUG-C1 fix, but existing rows created before
        // that fix (or any future data-migration edge case) could still
        // have a null totalAmount — guard it explicitly instead of NPEing.
        if (invoice.getTotalAmount() == null) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "This invoice has no total amount set and cannot accept a payment. Please correct the invoice first.");
        }

        PaymentParties parties = resolvePaymentParties(invoice, request);
        Distributor distributor = parties.distributor();
        Shop shop = parties.shop();
        com.spartan.dms.entity.SuperStockist superStockist = parties.superStockist();

        BigDecimal alreadyPaid = invoice.getPaidAmount() == null ? BigDecimal.ZERO : invoice.getPaidAmount();
        BigDecimal alreadyReturned = invoice.getReturnedAmount() == null ? BigDecimal.ZERO : invoice.getReturnedAmount();
        // BUG-H3 fix: outstanding must account for returnedAmount too, or a
        // shop/distributor could still be asked to pay the portion of the
        // invoice that was already returned.
        BigDecimal outstanding = invoice.getTotalAmount().subtract(alreadyPaid).subtract(alreadyReturned);
        if (request.getAmount().compareTo(outstanding) > 0) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "Payment amount (" + request.getAmount() + ") exceeds the outstanding balance (" + outstanding + ") for this invoice");
        }

        Payment payment = paymentMapper.toEntity(request);
        // paymentStatus on the Payment row itself reflects THIS payment's
        // own lifecycle (COMPLETED/REJECTED/VERIFIED, set via
        // verifyPayment()/rejectPayment()) — never trust whatever the
        // client sent here; a fresh payment always starts COMPLETED.
        payment.setPaymentStatus("COMPLETED");
        payment.setVerified(false);

        payment.setInvoice(invoice);
        payment.setDistributor(distributor);
        payment.setShop(shop);
        payment.setSuperStockist(superStockist);

        // Save Payment
        payment = paymentRepository.save(payment);

        // BUG-H3 fix: recalculateBalanceAndStatus() (see Invoice.java) is
        // the single formula used everywhere now — totalAmount - paidAmount
        // - returnedAmount — so a payment recorded after a sales return
        // can never again silently discard that return's effect.
        invoice.setPaidAmount(alreadyPaid.add(payment.getAmount()));
        invoice.recalculateBalanceAndStatus();
        invoiceRepository.save(invoice);

        auditLogService.log("CREATE", "PAYMENT", payment.getId(), "Recorded payment of " + payment.getAmount() + " for invoice " + invoice.getInvoiceNumber());

        return ApiResponse.<PaymentResponse>builder()
                .success(true)
                .message("Payment Created Successfully")
                .data(paymentMapper.toResponse(payment))
                .build();
    }

    // Which parties a payment needs -- and who's allowed to record it --
    // depends on which leg of Company -> Super Stockist -> Distributor ->
    // Shop the invoice is on. Shared by createPayment() and updatePayment()
    // so the two can't drift into accepting different combinations for the
    // same invoice level.
    private record PaymentParties(Distributor distributor, Shop shop, com.spartan.dms.entity.SuperStockist superStockist) {}

    private PaymentParties resolvePaymentParties(Invoice invoice, PaymentRequest request) {
        com.spartan.dms.enums.InvoiceLevel level = invoice.getInvoiceLevel();

        switch (level) {
            case DISTRIBUTOR_TO_SHOP -> {
                if (request.getDistributorId() == null || request.getShopId() == null) {
                    throw new com.spartan.dms.exception.BadRequestException(
                            "distributorId and shopId are required for a distributor-to-shop payment");
                }
                // A distributor can record a payment for their own shop/invoice,
                // but not on another distributor's behalf. Admin is unrestricted.
                securityUtils.assertDistributorAccess(request.getDistributorId());
                Distributor distributor = distributorRepository.findById(request.getDistributorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));
                Shop shop = shopRepository.findById(request.getShopId())
                        .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));
                return new PaymentParties(distributor, shop, null);
            }
            case SUPER_STOCKIST_TO_DISTRIBUTOR -> {
                if (request.getDistributorId() == null || request.getSuperStockistId() == null) {
                    throw new com.spartan.dms.exception.BadRequestException(
                            "distributorId and superStockistId are required for a super-stockist-to-distributor payment");
                }
                // The distributor is the payer here too.
                securityUtils.assertDistributorAccess(request.getDistributorId());
                Distributor distributor = distributorRepository.findById(request.getDistributorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));
                com.spartan.dms.entity.SuperStockist superStockist = superStockistRepository.findById(request.getSuperStockistId())
                        .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));
                return new PaymentParties(distributor, null, superStockist);
            }
            case COMPANY_TO_SUPER_STOCKIST -> {
                if (request.getSuperStockistId() == null) {
                    throw new com.spartan.dms.exception.BadRequestException(
                            "superStockistId is required for a company-to-super-stockist payment");
                }
                // The Super Stockist is the payer; only they (or an admin,
                // recording it on their behalf) can create this row.
                securityUtils.assertSuperStockistAccess(request.getSuperStockistId());
                com.spartan.dms.entity.SuperStockist superStockist = superStockistRepository.findById(request.getSuperStockistId())
                        .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));
                return new PaymentParties(null, null, superStockist);
            }
            default -> throw new com.spartan.dms.exception.BadRequestException("Unrecognized invoice level");
        }
    }

    // A payment's own party (distributor or superStockist -- whichever this
    // row actually has) determines who may view/act on it. Never assumes
    // distributor is set -- that's null for COMPANY_TO_SUPER_STOCKIST rows.
    private void assertPaymentAccess(Payment payment) {
        if (payment.getDistributor() != null) {
            securityUtils.assertDistributorAccess(payment.getDistributor().getId());
        } else if (payment.getSuperStockist() != null) {
            securityUtils.assertSuperStockistAccess(payment.getSuperStockist().getId());
        } else if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Not authorized to access this payment");
        }
    }

    /**
     * PDF of the payment register. Reuses getAllPayments() rather than
     * querying again, so the export is scoped to exactly the same rows the
     * caller can see on screen -- an admin gets everything, a Super
     * Stockist/Distributor only their own. Any change to that scoping
     * automatically applies here too.
     */
    public byte[] exportPaymentsPdf() {

        List<PaymentResponse> payments = getAllPayments().getData();

        String[] headers = {"Date", "Invoice", "Level", "Party", "Method", "Txn ID", "Status", "Amount"};
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;

        for (PaymentResponse p : payments) {
            String party = p.getShopName() != null ? p.getShopName()
                    : p.getDistributorName() != null ? p.getDistributorName()
                    : p.getSuperStockistName() != null ? p.getSuperStockistName() : "—";
            rows.add(new String[]{
                    p.getPaymentDate() != null ? p.getPaymentDate().toString() : "",
                    p.getInvoiceNumber() != null ? p.getInvoiceNumber() : "",
                    p.getInvoiceLevel() != null ? p.getInvoiceLevel() : "",
                    party,
                    p.getPaymentMethod() != null ? p.getPaymentMethod() : "",
                    p.getTransactionId() != null ? p.getTransactionId() : "",
                    p.getPaymentStatus() != null ? p.getPaymentStatus() : "",
                    p.getAmount() != null ? p.getAmount().toPlainString() : "0"
            });
            if (p.getAmount() != null) total = total.add(p.getAmount());
        }

        String[] totalRow = {"", "", "", "", "", "", "Total", total.toPlainString()};

        return pdfGenerator.generateReportTablePdf(
                "Payment Register",
                "Generated " + java.time.LocalDate.now() + " — " + rows.size() + " payment(s)",
                null, null, headers, rows, totalRow);
    }

    public ApiResponse<List<PaymentResponse>> getAllPayments() {

        List<Payment> paymentEntities;

        if (securityUtils.isSuperStockist()) {
            Long ssId = securityUtils.getScopedSuperStockistId();
            java.util.LinkedHashMap<Long, Payment> merged = new java.util.LinkedHashMap<>();
            for (Payment p : paymentRepository.findByDistributor_SuperStockist_Id(ssId)) {
                merged.put(p.getId(), p);
            }
            for (Payment p : paymentRepository.findBySuperStockistId(ssId)) {
                merged.put(p.getId(), p);
            }
            paymentEntities = new java.util.ArrayList<>(merged.values());
        } else {
            Long scopedDistributorId = securityUtils.getScopedDistributorId();
            paymentEntities = (scopedDistributorId != null)
                    ? paymentRepository.findByDistributorId(scopedDistributorId)
                    : paymentRepository.findAll();
        }

        List<PaymentResponse> payments = paymentEntities
                .stream()
                .map(paymentMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<PaymentResponse>>builder()
                .success(true)
                .message("Payment List")
                .data(payments)
                .build();
    }

    public ApiResponse<PaymentResponse> getPaymentById(Long id) {

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        assertPaymentAccess(payment);

        return ApiResponse.<PaymentResponse>builder()
                .success(true)
                .message("Payment Details")
                .data(paymentMapper.toResponse(payment))
                .build();
    }

    // BUG-H2 fix: editing a payment's amount used to overwrite payment.amount
    // with no validation at all and never touched the invoice's
    // paidAmount/balanceAmount/paymentStatus — the two silently desynced
    // forever. This now validates the new amount exactly like
    // createPayment() does, and resyncs whichever invoice(s) are affected
    // (almost always just the one payment already belonged to; handled
    // generically in case an admin also moves the payment to a different
    // invoiceId in the same edit).
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<PaymentResponse> updatePayment(Long id, PaymentRequest request) {

        if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Only an admin can update payments");
        }

        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new com.spartan.dms.exception.BadRequestException("Payment amount must be greater than zero");
        }

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        Invoice newInvoice = invoiceRepository.findById(request.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));

        Invoice oldInvoice = payment.getInvoice();
        BigDecimal oldAmount = payment.getAmount() != null ? payment.getAmount() : BigDecimal.ZERO;
        BigDecimal newAmount = request.getAmount();

        PaymentParties parties = resolvePaymentParties(newInvoice, request);

        paymentMapper.updateEntity(request, payment);
        payment.setAmount(newAmount);
        // A payment's own status/verification lifecycle is only ever
        // changed through verifyPayment()/rejectPayment() — an amount edit
        // must not silently re-verify or un-reject it.

        payment.setInvoice(newInvoice);
        payment.setDistributor(parties.distributor());
        payment.setShop(parties.shop());
        payment.setSuperStockist(parties.superStockist());

        if (oldInvoice != null && newInvoice.getId().equals(oldInvoice.getId())) {
            // Common case: same invoice, amount corrected. Validate against
            // the outstanding balance EXCLUDING this payment's own old
            // contribution, then re-add the new amount.
            BigDecimal paidExcludingThis = (oldInvoice.getPaidAmount() != null ? oldInvoice.getPaidAmount() : BigDecimal.ZERO)
                    .subtract(oldAmount);
            if (paidExcludingThis.compareTo(BigDecimal.ZERO) < 0) paidExcludingThis = BigDecimal.ZERO;
            BigDecimal returned = oldInvoice.getReturnedAmount() != null ? oldInvoice.getReturnedAmount() : BigDecimal.ZERO;
            BigDecimal total = oldInvoice.getTotalAmount() != null ? oldInvoice.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal outstandingExcludingThis = total.subtract(paidExcludingThis).subtract(returned);
            if (newAmount.compareTo(outstandingExcludingThis) > 0) {
                throw new com.spartan.dms.exception.BadRequestException(
                        "Payment amount (" + newAmount + ") exceeds the outstanding balance (" + outstandingExcludingThis + ") for this invoice");
            }
            oldInvoice.setPaidAmount(paidExcludingThis.add(newAmount));
            oldInvoice.recalculateBalanceAndStatus();
            invoiceRepository.save(oldInvoice);
        } else {
            // Rare case: the edit also moved the payment to a different
            // invoice — reverse the old amount off the old invoice (same
            // logic as deletePayment()) and apply the new amount to the
            // new invoice (same logic as createPayment()).
            if (oldInvoice != null) {
                BigDecimal paid = (oldInvoice.getPaidAmount() != null ? oldInvoice.getPaidAmount() : BigDecimal.ZERO).subtract(oldAmount);
                if (paid.compareTo(BigDecimal.ZERO) < 0) paid = BigDecimal.ZERO;
                oldInvoice.setPaidAmount(paid);
                oldInvoice.recalculateBalanceAndStatus();
                invoiceRepository.save(oldInvoice);
            }
            BigDecimal newPaid = (newInvoice.getPaidAmount() != null ? newInvoice.getPaidAmount() : BigDecimal.ZERO);
            BigDecimal newReturned = newInvoice.getReturnedAmount() != null ? newInvoice.getReturnedAmount() : BigDecimal.ZERO;
            BigDecimal newTotal = newInvoice.getTotalAmount() != null ? newInvoice.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal newOutstanding = newTotal.subtract(newPaid).subtract(newReturned);
            if (newAmount.compareTo(newOutstanding) > 0) {
                throw new com.spartan.dms.exception.BadRequestException(
                        "Payment amount (" + newAmount + ") exceeds the outstanding balance (" + newOutstanding + ") for the new invoice");
            }
            newInvoice.setPaidAmount(newPaid.add(newAmount));
            newInvoice.recalculateBalanceAndStatus();
            invoiceRepository.save(newInvoice);
        }

        payment = paymentRepository.save(payment);

        auditLogService.log("UPDATE", "PAYMENT", payment.getId(),
                "Updated payment amount from " + oldAmount + " to " + newAmount + " on invoice " + newInvoice.getInvoiceNumber());

        return ApiResponse.<PaymentResponse>builder()
                .success(true)
                .message("Payment Updated Successfully")
                .data(paymentMapper.toResponse(payment))
                .build();
    }

    // BUG-C2 / BUG-H5 fix: proof deletion, payment deletion, and the invoice
    // resync used to be three separate commits — a failure between them
    // could leave the invoice overstating paidAmount for a payment (or
    // proof) that no longer exists. @Transactional makes them one unit.
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<String> deletePayment(Long id) {

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        // The owning party may delete their own payment (e.g. wrong proof
        // uploaded, needs to redo it); assertPaymentAccess throws if it
        // belongs to someone else, and is a no-op for admins.
        assertPaymentAccess(payment);

        Invoice invoice = payment.getInvoice();
        BigDecimal deletedAmount = payment.getAmount() != null ? payment.getAmount() : BigDecimal.ZERO;

        // PaymentProof.payment_id is NOT NULL with no DB-level cascade —
        // deleting the Payment first throws a foreign-key violation
        // (surfaced to the user as a confusing generic "conflicts with an
        // existing record" error) whenever any proof was ever uploaded for
        // it. Clear the proofs first so the delete actually succeeds.
        List<PaymentProof> proofs = paymentProofRepository.findByPaymentId(payment.getId());
        if (!proofs.isEmpty()) {
            paymentProofRepository.deleteAll(proofs);
        }

        paymentRepository.delete(payment);

        // Reverse the exact sync createPayment() applied — deleting a
        // payment must roll the invoice's paid/balance/status back too,
        // or the invoice permanently overstates what's actually been paid.
        if (invoice != null) {
            BigDecimal paid = (invoice.getPaidAmount() == null ? BigDecimal.ZERO : invoice.getPaidAmount())
                    .subtract(deletedAmount);
            if (paid.compareTo(BigDecimal.ZERO) < 0) paid = BigDecimal.ZERO;
            invoice.setPaidAmount(paid);
            invoice.recalculateBalanceAndStatus();
            invoiceRepository.save(invoice);
        }

        auditLogService.log("DELETE", "PAYMENT", id, "Deleted payment of " + deletedAmount
                + (invoice != null ? " for invoice " + invoice.getInvoiceNumber() : ""));

        return ApiResponse.<String>builder()
                .success(true)
                .message("Payment Deleted Successfully")
                .data("Deleted")
                .build();
    }
    public ApiResponse<List<PaymentResponse>> getPaymentsByInvoice(Long invoiceId) {

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));

        if (invoice.getDistributor() != null) {
            securityUtils.assertDistributorAccess(invoice.getDistributor().getId());
        } else if (invoice.getSuperStockist() != null) {
            securityUtils.assertSuperStockistAccess(invoice.getSuperStockist().getId());
        } else if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Not authorized to access this invoice's payments");
        }

        List<PaymentResponse> payments = paymentRepository.findByInvoiceId(invoiceId)
                .stream()
                .map(paymentMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<PaymentResponse>>builder()
                .success(true)
                .message("Invoice Payments")
                .data(payments)
                .build();
    }

    /**
     * PaymentController exposes this at POST /payments/{id}/upload-proof.
     * It used to have its own broken copy of the upload logic (discarded
     * the file bytes, stored only the raw filename string). That duplicate
     * logic has been removed — this now delegates to
     * PaymentProofService.uploadProof(), which is the single place that
     * actually validates, stores, and records an uploaded proof file. Both
     * this endpoint and /api/v1/payment-proofs/{id}/upload now behave
     * identically and stay in sync by construction.
     */
    public ApiResponse<String> uploadPaymentProof(Long id, MultipartFile file) {

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        assertPaymentAccess(payment);

        ApiResponse<PaymentProofResponse> result = paymentProofService.uploadProof(id, file);

        return ApiResponse.<String>builder()
                .success(result.isSuccess())
                .message(result.getMessage())
                .data(result.getData() != null ? result.getData().getFileUrl() : null)
                .build();
    }

    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<String> verifyPayment(Long id) {

        if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Only an admin can verify payments");
        }

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if ("REJECTED".equalsIgnoreCase(payment.getPaymentStatus())) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "This payment was rejected. Re-record it as a new payment instead of verifying the rejected one.");
        }

        payment.setVerified(true);
        payment.setPaymentStatus("VERIFIED");

        paymentRepository.save(payment);

        auditLogService.log("VERIFY", "PAYMENT", payment.getId(), "Verified payment of " + payment.getAmount());

        return ApiResponse.<String>builder()
                .success(true)
                .message("Payment Verified Successfully")
                .data("Success")
                .build();
    }

    // BUG-H1 fix: rejecting a payment used to only flip the Payment row's
    // own status/verified flag and never touched the invoice at all — so
    // money that was rejected (i.e. never actually collectable) stayed
    // counted in Invoice.paidAmount, and therefore in every Dashboard total
    // derived from it, forever. This now reverses the payment's amount off
    // the invoice exactly like deletePayment() does (the payment ROW itself
    // is kept, marked REJECTED, for audit history — only its effect on the
    // invoice's running totals is undone).
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<String> rejectPayment(Long id) {

        if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Only an admin can reject payments");
        }

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if ("REJECTED".equalsIgnoreCase(payment.getPaymentStatus())) {
            // Already rejected — its amount was already reversed off the
            // invoice the first time; rejecting it again must not subtract
            // the amount a second time.
            return ApiResponse.<String>builder()
                    .success(true)
                    .message("Payment was already rejected")
                    .data("Success")
                    .build();
        }

        payment.setVerified(false);
        payment.setPaymentStatus("REJECTED");
        paymentRepository.save(payment);

        // Reached this point only when the payment was NOT already
        // REJECTED (see the early-return above), so its amount is
        // guaranteed to still be counted in invoice.paidAmount and must be
        // reversed exactly once.
        Invoice invoice = payment.getInvoice();
        if (invoice != null) {
            BigDecimal amount = payment.getAmount() != null ? payment.getAmount() : BigDecimal.ZERO;
            BigDecimal paid = (invoice.getPaidAmount() != null ? invoice.getPaidAmount() : BigDecimal.ZERO).subtract(amount);
            if (paid.compareTo(BigDecimal.ZERO) < 0) paid = BigDecimal.ZERO;
            invoice.setPaidAmount(paid);
            invoice.recalculateBalanceAndStatus();
            invoiceRepository.save(invoice);
        }

        auditLogService.log("REJECT", "PAYMENT", payment.getId(),
                "Rejected payment of " + payment.getAmount()
                        + (invoice != null ? " for invoice " + invoice.getInvoiceNumber() + " — invoice totals recalculated" : ""));

        return ApiResponse.<String>builder()
                .success(true)
                .message("Payment Rejected Successfully")
                .data("Success")
                .build();
    }

    // Free-text status was previously accepted with no validation and could
    // silently desync from the invoice (e.g. setting a payment back to
    // "COMPLETED" after it was reversed as REJECTED, without re-adding its
    // amount to the invoice). Route the two states that have real financial
    // consequences through the methods that keep the invoice in sync, and
    // reject anything else — this endpoint is not meant to bypass them.
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<String> updatePaymentStatus(Long id, String status) {

        if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Only an admin can update payment status");
        }
        if (status == null) {
            throw new com.spartan.dms.exception.BadRequestException("status is required");
        }
        String normalized = status.trim().toUpperCase();
        if ("REJECTED".equals(normalized)) {
            return rejectPayment(id);
        }
        if ("VERIFIED".equals(normalized)) {
            return verifyPayment(id);
        }
        if (!"COMPLETED".equals(normalized) && !"PENDING".equals(normalized)) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "Invalid payment status: " + status + ". Use COMPLETED, PENDING, VERIFIED, or REJECTED.");
        }

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if ("REJECTED".equalsIgnoreCase(payment.getPaymentStatus())) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "This payment was rejected and its amount removed from the invoice — use a new payment instead of changing its status back.");
        }

        payment.setPaymentStatus(normalized);
        paymentRepository.save(payment);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Payment Status Updated Successfully")
                .data("Success")
                .build();
    }
}
