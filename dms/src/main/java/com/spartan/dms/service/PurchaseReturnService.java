package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.PurchaseReturnRequest;
import com.spartan.dms.dto.PurchaseReturnResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.Product;
import com.spartan.dms.entity.PurchaseReturn;
import com.spartan.dms.entity.SuperStockist;
import com.spartan.dms.entity.Warehouse;
import com.spartan.dms.enums.LedgerTransactionType;
import com.spartan.dms.enums.OwnerType;
import com.spartan.dms.enums.ReturnLevel;
import com.spartan.dms.exception.BadRequestException;
import com.spartan.dms.exception.ForbiddenException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.repository.ProductRepository;
import com.spartan.dms.repository.PurchaseReturnRepository;
import com.spartan.dms.repository.SuperStockistRepository;
import com.spartan.dms.repository.WarehouseRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Returns travelling UP the chain. One PurchaseReturn row is both a
 * Purchase Return (sender) and a Sales Return Received (receiver) -- see
 * ReturnLevel -- so stock is debited from the sender and credited to the
 * receiver in the same transaction, with a matching OUT/IN pair of Product
 * Ledger entries at the two real locations involved.
 *
 * This mirrors the pattern InvoiceService.applyStockForInvoiceLine() and
 * ProductRequestService.fulfill() already use for downward movement, just
 * in the opposite direction, so every stock-moving path in this codebase
 * stays consistent about WHERE stock actually lives:
 *   Company        -> Product.stockQuantity
 *   Super Stockist -> Warehouse row (ownerType SUPER_STOCKIST)
 *   Distributor    -> Warehouse row (ownerType DISTRIBUTOR)
 *
 * Admin never files a return here -- Company is the top of the chain, so
 * there is nowhere further up to return to. Admin only reads the history
 * (see getCompanyInboundReturns / getAllReturns).
 */
@Service
@RequiredArgsConstructor
public class PurchaseReturnService {

    private final PurchaseReturnRepository purchaseReturnRepository;
    private final ProductRepository productRepository;
    private final SuperStockistRepository superStockistRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductLedgerService productLedgerService;
    private final com.spartan.dms.repository.StockMovementRepository stockMovementRepository;
    private final com.spartan.dms.repository.DistributorRepository distributorRepository;
    private final com.spartan.dms.mapper.PurchaseReturnMapper purchaseReturnMapper;
    private final com.spartan.dms.repository.ProductDistributorPriceRepository productDistributorPriceRepository;
    private final com.spartan.dms.repository.ProductSuperStockistPriceRepository productSuperStockistPriceRepository;
    private final SecurityUtils securityUtils;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final com.spartan.dms.util.PdfGenerator pdfGenerator;

    /* ==================== CREATE ==================== */

    /**
     * Distributor -> Super Stockist. Debits the Distributor's warehouse,
     * credits their Super Stockist's warehouse.
     */
    @Transactional
    public ApiResponse<PurchaseReturnResponse> createDistributorPurchaseReturn(PurchaseReturnRequest request) {

        Long distributorId = securityUtils.getScopedDistributorId();
        if (distributorId == null) {
            throw new ForbiddenException(
                    "Only a Distributor login can file a Distributor purchase return. "
                            + "An admin should use the return history views instead.");
        }
        Distributor distributor = distributorRepository.findById(distributorId)
                .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));

        SuperStockist superStockist = distributor.getSuperStockist();
        if (superStockist == null) {
            throw new BadRequestException(
                    "Your distributor account isn't assigned to a Super Stockist yet, so there's nobody to return stock to. "
                            + "Ask an admin to assign you first.");
        }

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        int qty = validatedQuantity(request);
        // Price and total are resolved ENTIRELY server-side from the
        // product + this distributor's own rate. Nothing price-related is
        // read from the request, so a tampered client cannot influence the
        // credited amount.
        BigDecimal unitPrice = resolveRolePrice(product, ReturnLevel.DISTRIBUTOR_TO_SUPER_STOCKIST, distributor, null);

        // Reason is resolved BEFORE any stock is touched. Both create
        // methods are @Transactional so a late failure would roll back
        // anyway, but validating every input up front means the failure
        // path never depends on the rollback working -- and the error the
        // caller sees is the real one ("describe the reason"), not a
        // confusing stock message from a half-applied write.
        PurchaseReturn.PurchaseReturnBuilder builder = PurchaseReturn.builder();
        String reasonText = resolveReason(request, builder);

        // Availability is checked NOW so the submitter gets an immediate,
        // useful error -- but no stock moves yet. The return is only a
        // request until the receiving party approves it; every stock,
        // warehouse and ledger effect happens once, in applyReturnEffects()
        // on approval, which re-checks availability because stock can
        // change while a request sits pending.
        assertHasStock(OwnerType.DISTRIBUTOR, null, distributor, product, qty);

        PurchaseReturn pr = purchaseReturnRepository.save(builder
                .status(com.spartan.dms.enums.ReturnStatus.PENDING)
                .returnLevel(ReturnLevel.DISTRIBUTOR_TO_SUPER_STOCKIST)
                .distributor(distributor)
                .superStockist(superStockist)
                .product(product)
                .quantity(qty)
                .unitPrice(unitPrice)
                .returnAmount(unitPrice.multiply(BigDecimal.valueOf(qty)))
                .returnDate(LocalDate.now())
                .reason(reasonText)
                .createdBy(currentUsernameOrSystem())
                .build());
        pr.setReturnNumber("PR-" + pr.getId());
        pr = purchaseReturnRepository.save(pr);

        auditLogService.log("CREATE", "PURCHASE_RETURN", pr.getId(),
                "Distributor purchase return " + pr.getReturnNumber() + " submitted to "
                        + superStockist.getSuperStockistName() + " (pending approval)");

        notificationService.notify(SecurityUtils.ROLE_SUPER_STOCKIST, superStockist.getId(),
                "Return request awaiting your approval",
                distributor.getDistributorName() + " wants to return " + qty + " x " + product.getProductName()
                        + " (" + pr.getReturnNumber() + "). Approve it to accept the stock.",
                "RETURN", pr.getId());

        return ApiResponse.<PurchaseReturnResponse>builder()
                .success(true)
                .message("Return request submitted — awaiting approval")
                .data(toResponse(pr))
                .build();
    }

    /**
     * Super Stockist -> Company. Debits the Super Stockist's warehouse,
     * credits Company root stock (Product.stockQuantity).
     */
    @Transactional
    public ApiResponse<PurchaseReturnResponse> createSuperStockistPurchaseReturn(PurchaseReturnRequest request) {

        Long superStockistId = securityUtils.isSuperStockist() ? securityUtils.getScopedSuperStockistId() : null;
        if (superStockistId == null) {
            throw new ForbiddenException(
                    "Only a Super Stockist login can file a Super Stockist purchase return. "
                            + "An admin should use the return history views instead.");
        }
        SuperStockist superStockist = superStockistRepository.findById(superStockistId)
                .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        int qty = validatedQuantity(request);
        BigDecimal unitPrice = resolveRolePrice(product, ReturnLevel.SUPER_STOCKIST_TO_COMPANY, null, superStockist);

        // Reason is resolved BEFORE any stock is touched. Both create
        // methods are @Transactional so a late failure would roll back
        // anyway, but validating every input up front means the failure
        // path never depends on the rollback working -- and the error the
        // caller sees is the real one ("describe the reason"), not a
        // confusing stock message from a half-applied write.
        PurchaseReturn.PurchaseReturnBuilder builder = PurchaseReturn.builder();
        String reasonText = resolveReason(request, builder);

        // Availability is checked NOW so the submitter gets an immediate,
        // useful error -- but no stock moves yet. This return stays PENDING
        // until an Admin approves it; every stock, warehouse and ledger
        // effect happens once, in applyReturnEffects(), which re-checks
        // availability because stock can change while it sits pending.
        assertHasStock(OwnerType.SUPER_STOCKIST, superStockist, null, product, qty);

        PurchaseReturn pr = purchaseReturnRepository.save(builder
                .status(com.spartan.dms.enums.ReturnStatus.PENDING)
                .returnLevel(ReturnLevel.SUPER_STOCKIST_TO_COMPANY)
                .distributor(null)
                .superStockist(superStockist)
                .product(product)
                .quantity(qty)
                .unitPrice(unitPrice)
                .returnAmount(unitPrice.multiply(BigDecimal.valueOf(qty)))
                .returnDate(LocalDate.now())
                .reason(reasonText)
                .createdBy(currentUsernameOrSystem())
                .build());
        pr.setReturnNumber("PR-" + pr.getId());
        pr = purchaseReturnRepository.save(pr);

        auditLogService.log("CREATE", "PURCHASE_RETURN", pr.getId(),
                "Super Stockist purchase return " + pr.getReturnNumber() + " submitted to Company (pending approval)");

        // Admin-facing notification: recipientRole null = the global admin
        // feed (see NotificationService.getMyNotifications).
        notificationService.notify(null, null,
                "Return request awaiting approval",
                superStockist.getSuperStockistName() + " wants to return " + qty + " x "
                        + product.getProductName() + " (" + pr.getReturnNumber() + ").",
                "RETURN", pr.getId());

        return ApiResponse.<PurchaseReturnResponse>builder()
                .success(true)
                .message("Return request submitted — awaiting admin approval")
                .data(toResponse(pr))
                .build();
    }

    /* ==================== EXPORT ==================== */

    /**
     * PDF of returns travelling up the chain. `scope` picks which view to
     * print, and each one delegates to the existing read method, so the
     * export inherits that method's role scoping instead of re-deriving
     * it (and a caller can never print rows they can't already see).
     */
    public byte[] exportReturnsPdf(String scope) {

        String key = scope == null ? "" : scope.trim().toLowerCase();
        List<PurchaseReturnResponse> rowsData;
        String title;

        switch (key) {
            case "distributor" -> {
                rowsData = getMyDistributorPurchaseReturns().getData();
                title = "Purchase Return Register";
            }
            case "super-stockist" -> {
                rowsData = getMySuperStockistPurchaseReturns().getData();
                title = "Purchase Return Register";
            }
            case "received" -> {
                rowsData = getMySuperStockistSalesReturns().getData();
                title = "Sales Return Register (Received)";
            }
            case "company-received" -> {
                rowsData = getCompanyInboundReturns().getData();
                title = "Sales Return Register (Received into Company)";
            }
            case "history" -> {
                rowsData = getAllReturns().getData();
                title = "Return History";
            }
            default -> throw new BadRequestException(
                    "Unknown export scope '" + scope + "'. Expected one of: "
                            + "distributor, super-stockist, received, company-received, history.");
        }

        String[] headers = {"Return #", "Date", "From", "To", "Product", "Qty", "Unit Price", "Amount", "Status", "Reason"};
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;

        for (PurchaseReturnResponse r : rowsData) {
            rows.add(new String[]{
                    r.getReturnNumber() != null ? r.getReturnNumber() : "",
                    r.getReturnDate() != null ? r.getReturnDate().toString() : "",
                    r.getFromParty() != null ? r.getFromParty() : "",
                    r.getToParty() != null ? r.getToParty() : "",
                    r.getProductName() != null ? r.getProductName() : "",
                    String.valueOf(r.getQuantity()),
                    r.getUnitPrice() != null ? r.getUnitPrice().toPlainString() : "",
                    r.getReturnAmount() != null ? r.getReturnAmount().toPlainString() : "0",
                    r.getStatus() != null ? r.getStatus() : "PENDING",
                    r.getReason() != null ? r.getReason() : ""
            });
            // Only approved returns actually moved goods, so a total that
            // included pending/rejected rows would overstate the value.
            if ("APPROVED".equalsIgnoreCase(r.getStatus()) && r.getReturnAmount() != null) {
                total = total.add(r.getReturnAmount());
            }
        }

        String[] totalRow = {"", "", "", "", "", "", "Approved total", total.toPlainString(), "", ""};

        return pdfGenerator.generateReportTablePdf(
                title,
                "Generated " + java.time.LocalDate.now() + " — " + rows.size() + " return(s)",
                null, null, headers, rows, totalRow);
    }

    /* ==================== APPROVAL ==================== */

    /**
     * Approving is what actually moves the goods. Everything a return
     * affects -- both warehouses, the Product Ledger pair, Company root
     * stock and its StockMovement row -- happens here, exactly once, so a
     * PENDING or REJECTED return can never influence stock, dashboards or
     * reports.
     *
     * Who may approve is decided by which leg the return is on: the
     * RECEIVING party signs off, because they're the one accepting goods
     * back into their own stock. A Super Stockist approves their
     * distributors' returns; only an Admin approves a Super Stockist's
     * return into Company stock.
     */
    @Transactional
    public ApiResponse<PurchaseReturnResponse> approveReturn(Long id) {

        PurchaseReturn pr = purchaseReturnRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Return not found"));

        assertCanDecide(pr);
        assertPending(pr);

        applyReturnEffects(pr);

        pr.setStatus(com.spartan.dms.enums.ReturnStatus.APPROVED);
        pr.setApprovedBy(currentUsernameOrSystem());
        pr.setDecidedAt(java.time.LocalDateTime.now());
        pr = purchaseReturnRepository.save(pr);

        auditLogService.log("APPROVE", "PURCHASE_RETURN", pr.getId(),
                "Approved return " + pr.getReturnNumber() + " — stock and ledgers updated");

        notifySubmitter(pr, "Return approved",
                "Your return " + pr.getReturnNumber() + " was approved and the stock has been transferred.");

        return ApiResponse.<PurchaseReturnResponse>builder()
                .success(true)
                .message("Return approved — stock updated")
                .data(toResponse(pr))
                .build();
    }

    /**
     * Rejecting touches no stock at all -- a pending return never moved
     * any, so there is nothing to reverse. It only records the decision
     * and tells the submitter why.
     */
    @Transactional
    public ApiResponse<PurchaseReturnResponse> rejectReturn(Long id, String reason) {

        PurchaseReturn pr = purchaseReturnRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Return not found"));

        assertCanDecide(pr);
        assertPending(pr);

        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Please give a reason so the submitter knows why this was rejected.");
        }

        pr.setStatus(com.spartan.dms.enums.ReturnStatus.REJECTED);
        pr.setApprovedBy(currentUsernameOrSystem());
        pr.setDecidedAt(java.time.LocalDateTime.now());
        pr.setRejectionReason(reason.trim());
        pr = purchaseReturnRepository.save(pr);

        auditLogService.log("REJECT", "PURCHASE_RETURN", pr.getId(),
                "Rejected return " + pr.getReturnNumber() + " — no stock moved");

        notifySubmitter(pr, "Return rejected",
                "Your return " + pr.getReturnNumber() + " was rejected: " + pr.getRejectionReason());

        return ApiResponse.<PurchaseReturnResponse>builder()
                .success(true)
                .message("Return rejected")
                .data(toResponse(pr))
                .build();
    }

    /** Pending returns awaiting THIS caller's decision. */
    public ApiResponse<List<PurchaseReturnResponse>> getPendingForMe() {
        if (securityUtils.isAdmin()) {
            return wrap(purchaseReturnRepository.findByReturnLevelAndStatusOrderByReturnDateDescIdDesc(
                    ReturnLevel.SUPER_STOCKIST_TO_COMPANY, com.spartan.dms.enums.ReturnStatus.PENDING),
                    "Returns awaiting your approval");
        }
        if (securityUtils.isSuperStockist()) {
            return wrap(purchaseReturnRepository
                            .findBySuperStockistIdAndReturnLevelAndStatusOrderByReturnDateDescIdDesc(
                                    securityUtils.getScopedSuperStockistId(),
                                    ReturnLevel.DISTRIBUTOR_TO_SUPER_STOCKIST,
                                    com.spartan.dms.enums.ReturnStatus.PENDING),
                    "Returns awaiting your approval");
        }
        throw new ForbiddenException("Only the receiving party approves returns");
    }

    // The receiving party decides -- they're the one taking goods back
    // into their own stock. Admin can also decide on a distributor-level
    // return as a fallback (someone has to be able to unblock a stuck
    // request if a Super Stockist account is disabled).
    private void assertCanDecide(PurchaseReturn pr) {
        if (securityUtils.isAdmin()) {
            return;
        }
        if (pr.getReturnLevel() == ReturnLevel.DISTRIBUTOR_TO_SUPER_STOCKIST
                && securityUtils.isSuperStockist()
                && pr.getSuperStockist() != null
                && pr.getSuperStockist().getId().equals(securityUtils.getScopedSuperStockistId())) {
            return;
        }
        throw new ForbiddenException("You are not the approver for this return");
    }

    private void assertPending(PurchaseReturn pr) {
        if (pr.getStatus() != com.spartan.dms.enums.ReturnStatus.PENDING) {
            throw new BadRequestException("This return has already been "
                    + pr.getStatus().name().toLowerCase() + " — it can't be decided again.");
        }
    }

    private void notifySubmitter(PurchaseReturn pr, String title, String message) {
        if (pr.getReturnLevel() == ReturnLevel.DISTRIBUTOR_TO_SUPER_STOCKIST && pr.getDistributor() != null) {
            notificationService.notify(SecurityUtils.ROLE_DISTRIBUTOR, pr.getDistributor().getId(),
                    title, message, "RETURN", pr.getId());
        } else if (pr.getSuperStockist() != null) {
            notificationService.notify(SecurityUtils.ROLE_SUPER_STOCKIST, pr.getSuperStockist().getId(),
                    title, message, "RETURN", pr.getId());
        }
    }

    /**
     * The single place a return's stock actually moves. Re-checks
     * availability first: the request may have sat pending while the
     * submitter sold or transferred that same stock, and approving a
     * return they can no longer cover would drive their warehouse
     * negative.
     */
    private void applyReturnEffects(PurchaseReturn pr) {

        Product product = pr.getProduct();
        int qty = pr.getQuantity();

        if (pr.getReturnLevel() == ReturnLevel.DISTRIBUTOR_TO_SUPER_STOCKIST) {

            Distributor distributor = pr.getDistributor();
            SuperStockist superStockist = pr.getSuperStockist();

            Warehouse distWarehouse = warehouseRepository
                    .findByDistributorIdAndProductId(distributor.getId(), product.getId())
                    .orElseThrow(() -> new BadRequestException(
                            distributor.getDistributorName() + " no longer has any "
                                    + product.getProductName() + " in stock, so this return can't be approved."));
            if (distWarehouse.getQuantity() < qty) {
                throw new BadRequestException(distributor.getDistributorName() + " now only has "
                        + distWarehouse.getQuantity() + " of " + product.getProductName()
                        + " on hand, so this return of " + qty + " can't be approved.");
            }
            distWarehouse.setQuantity(distWarehouse.getQuantity() - qty);
            warehouseRepository.save(distWarehouse);

            creditWarehouse(OwnerType.SUPER_STOCKIST, superStockist, null, product, qty);

            writeLedgerPair(pr, OwnerType.DISTRIBUTOR, distributor, null,
                    OwnerType.SUPER_STOCKIST, null, superStockist);
            return;
        }

        // SUPER_STOCKIST_TO_COMPANY
        SuperStockist superStockist = pr.getSuperStockist();

        Warehouse ssWarehouse = warehouseRepository
                .findBySuperStockistIdAndProductId(superStockist.getId(), product.getId())
                .orElseThrow(() -> new BadRequestException(
                        superStockist.getSuperStockistName() + " no longer has any "
                                + product.getProductName() + " in stock, so this return can't be approved."));
        if (ssWarehouse.getQuantity() < qty) {
            throw new BadRequestException(superStockist.getSuperStockistName() + " now only has "
                    + ssWarehouse.getQuantity() + " of " + product.getProductName()
                    + " on hand, so this return of " + qty + " can't be approved.");
        }
        ssWarehouse.setQuantity(ssWarehouse.getQuantity() - qty);
        warehouseRepository.save(ssWarehouse);

        // Company has no Warehouse row -- its stock lives on the product.
        int companyQty = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        product.setStockQuantity(companyQty + qty);
        productRepository.save(product);

        writeLedgerPair(pr, OwnerType.SUPER_STOCKIST, null, superStockist,
                OwnerType.COMPANY, null, null);

        // Only leg that changes Product.stockQuantity, which is what the
        // Stock Summary report is built from, so it gets a StockMovement row.
        stockMovementRepository.save(com.spartan.dms.entity.StockMovement.builder()
                .product(product)
                .movementType(com.spartan.dms.entity.StockMovement.MovementType.INWARD)
                .quantity(BigDecimal.valueOf(qty))
                .rate(pr.getUnitPrice())
                .value(pr.getUnitPrice().multiply(BigDecimal.valueOf(qty)))
                .movementDate(LocalDate.now())
                .referenceType("PURCHASE_RETURN")
                .referenceId(pr.getReturnNumber())
                .remarks("Return from " + superStockist.getSuperStockistName())
                .build());
    }

    // Submit-time availability check. Deliberately does NOT mutate
    // anything -- applyReturnEffects() does the real debit on approval.
    private void assertHasStock(OwnerType ownerType, SuperStockist superStockist,
                                 Distributor distributor, Product product, int qty) {
        java.util.Optional<Warehouse> found = ownerType == OwnerType.SUPER_STOCKIST
                ? warehouseRepository.findBySuperStockistIdAndProductId(superStockist.getId(), product.getId())
                : warehouseRepository.findByDistributorIdAndProductId(distributor.getId(), product.getId());

        if (found.isEmpty()) {
            throw new BadRequestException("You have no stock of " + product.getProductName() + " on hand to return.");
        }
        if (found.get().getQuantity() < qty) {
            throw new BadRequestException("You only have " + found.get().getQuantity() + " of "
                    + product.getProductName() + " on hand — can't return " + qty + ".");
        }
    }

    /* ==================== READ ==================== */

    /** Distributor: purchase returns I sent up to my Super Stockist. */
    public ApiResponse<List<PurchaseReturnResponse>> getMyDistributorPurchaseReturns() {
        Long distributorId = securityUtils.getScopedDistributorId();
        if (distributorId == null) {
            throw new ForbiddenException("Only a Distributor login has distributor purchase returns");
        }
        return wrap(purchaseReturnRepository.findByDistributorIdOrderByReturnDateDescIdDesc(distributorId),
                "My purchase returns");
    }

    /** Super Stockist: purchase returns I sent up to Company. */
    public ApiResponse<List<PurchaseReturnResponse>> getMySuperStockistPurchaseReturns() {
        Long ssId = securityUtils.isSuperStockist() ? securityUtils.getScopedSuperStockistId() : null;
        if (ssId == null) {
            throw new ForbiddenException("Only a Super Stockist login has super stockist purchase returns");
        }
        return wrap(purchaseReturnRepository.findBySuperStockistIdAndReturnLevelOrderByReturnDateDescIdDesc(
                ssId, ReturnLevel.SUPER_STOCKIST_TO_COMPANY), "My purchase returns");
    }

    /**
     * Super Stockist: sales returns RECEIVED from my distributors -- the
     * same rows their distributors filed as purchase returns, read from
     * the receiving side.
     */
    public ApiResponse<List<PurchaseReturnResponse>> getMySuperStockistSalesReturns() {
        Long ssId = securityUtils.isSuperStockist() ? securityUtils.getScopedSuperStockistId() : null;
        if (ssId == null) {
            throw new ForbiddenException("Only a Super Stockist login has received sales returns");
        }
        return wrap(purchaseReturnRepository.findBySuperStockistIdAndReturnLevelOrderByReturnDateDescIdDesc(
                ssId, ReturnLevel.DISTRIBUTOR_TO_SUPER_STOCKIST), "Sales returns received");
    }

    /** Admin: sales returns received into Company from Super Stockists. */
    public ApiResponse<List<PurchaseReturnResponse>> getCompanyInboundReturns() {
        securityUtils.assertAdmin();
        return wrap(purchaseReturnRepository.findByReturnLevelOrderByReturnDateDescIdDesc(
                ReturnLevel.SUPER_STOCKIST_TO_COMPANY), "Sales returns received");
    }

    /** Admin: complete return history across every level. */
    public ApiResponse<List<PurchaseReturnResponse>> getAllReturns() {
        securityUtils.assertAdmin();
        return wrap(purchaseReturnRepository.findByOrderByReturnDateDescIdDesc(), "Return history");
    }

    /** Detail view, access-scoped to whichever parties this return involves. */
    public ApiResponse<PurchaseReturnResponse> getById(Long id) {
        PurchaseReturn pr = purchaseReturnRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Return not found"));

        if (!securityUtils.isAdmin()) {
            if (securityUtils.isSuperStockist()) {
                Long myId = securityUtils.getScopedSuperStockistId();
                if (pr.getSuperStockist() == null || !pr.getSuperStockist().getId().equals(myId)) {
                    throw new ForbiddenException("You do not have access to this return");
                }
            } else {
                Long myId = securityUtils.getScopedDistributorId();
                if (pr.getDistributor() == null || !pr.getDistributor().getId().equals(myId)) {
                    throw new ForbiddenException("You do not have access to this return");
                }
            }
        }

        return ApiResponse.<PurchaseReturnResponse>builder()
                .success(true)
                .message("Return details")
                .data(toResponse(pr))
                .build();
    }

    /* ==================== DELETE (reversal) ==================== */

    /**
     * Deletes a return and puts the goods back exactly where they were.
     *
     * There is deliberately no update(): changing the product or quantity
     * on a posted return would mean silently re-writing stock at two
     * locations plus the immutable Product Ledger rows it already wrote.
     * Delete-and-re-enter keeps the ledger honest -- the reversal is
     * recorded as its own STOCK_ADJUSTMENT pair rather than erasing
     * history, matching how InvoiceService handles invoice deletion.
     *
     * Only the party who SENT the goods (or an admin) may delete, and only
     * while the receiver still has the stock on hand -- once they've moved
     * it on, reversing would drive their balance negative, so we refuse
     * instead of corrupting it.
     */
    @Transactional
    public ApiResponse<String> deletePurchaseReturn(Long id) {

        PurchaseReturn pr = purchaseReturnRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Return not found"));

        assertCanDelete(pr);

        // Only an APPROVED return ever moved stock, so only an approved one
        // has anything to reverse. Blindly running the reversal below on a
        // PENDING or REJECTED return would subtract inventory that was
        // never added -- silently corrupting both warehouses.
        String number = pr.getReturnNumber();
        if (pr.getStatus() != com.spartan.dms.enums.ReturnStatus.APPROVED) {
            purchaseReturnRepository.delete(pr);
            auditLogService.log("DELETE", "PURCHASE_RETURN", id,
                    "Deleted " + pr.getStatus().name().toLowerCase() + " return " + number + " (no stock to reverse)");
            return ApiResponse.<String>builder()
                    .success(true)
                    .message("Return deleted")
                    .data("Deleted")
                    .build();
        }

        Product product = pr.getProduct();
        int qty = pr.getQuantity();

        if (pr.getReturnLevel() == ReturnLevel.DISTRIBUTOR_TO_SUPER_STOCKIST) {
            // Take it back off the Super Stockist...
            Warehouse ssWarehouse = warehouseRepository
                    .findBySuperStockistIdAndProductId(pr.getSuperStockist().getId(), product.getId())
                    .orElseThrow(() -> new BadRequestException(
                            "Cannot reverse: the receiving Super Stockist no longer holds this product."));
            if (ssWarehouse.getQuantity() < qty) {
                throw new BadRequestException("Cannot reverse: the Super Stockist has only "
                        + ssWarehouse.getQuantity() + " of " + product.getProductName()
                        + " left and has already moved the rest on.");
            }
            ssWarehouse.setQuantity(ssWarehouse.getQuantity() - qty);
            warehouseRepository.save(ssWarehouse);

            // ...and give it back to the Distributor.
            creditWarehouse(OwnerType.DISTRIBUTOR, null, pr.getDistributor(), product, qty);

            writeReversalPair(pr, OwnerType.SUPER_STOCKIST, null, pr.getSuperStockist(),
                    OwnerType.DISTRIBUTOR, pr.getDistributor(), null);
        } else {
            // Take it back off Company root stock...
            int companyQty = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
            if (companyQty < qty) {
                throw new BadRequestException("Cannot reverse: Company holds only " + companyQty
                        + " of " + product.getProductName() + " and has already moved the rest on.");
            }
            product.setStockQuantity(companyQty - qty);
            productRepository.save(product);

            // ...and give it back to the Super Stockist.
            creditWarehouse(OwnerType.SUPER_STOCKIST, pr.getSuperStockist(), null, product, qty);

            writeReversalPair(pr, OwnerType.COMPANY, null, null,
                    OwnerType.SUPER_STOCKIST, null, pr.getSuperStockist());

            // Mirror the StockMovement row the original return wrote, so
            // the company-level Stock Summary report stays reconciled.
            stockMovementRepository.save(com.spartan.dms.entity.StockMovement.builder()
                    .product(product)
                    .movementType(com.spartan.dms.entity.StockMovement.MovementType.OUTWARD)
                    .quantity(BigDecimal.valueOf(qty))
                    .rate(pr.getUnitPrice())
                    .value(pr.getUnitPrice().multiply(BigDecimal.valueOf(qty)))
                    .movementDate(LocalDate.now())
                    .referenceType("PURCHASE_RETURN_REVERSAL")
                    .referenceId(pr.getReturnNumber())
                    .remarks("Reversal — return " + pr.getReturnNumber() + " deleted")
                    .build());
        }

        purchaseReturnRepository.delete(pr);

        auditLogService.log("DELETE", "PURCHASE_RETURN", id, "Deleted return " + number + " and reversed stock");

        return ApiResponse.<String>builder()
                .success(true)
                .message("Return " + number + " deleted and stock reversed")
                .data("Deleted")
                .build();
    }

    // The sending party owns the record; the receiver can't delete goods
    // out from under them, and neither can an unrelated party.
    private void assertCanDelete(PurchaseReturn pr) {
        if (securityUtils.isAdmin()) {
            return;
        }
        if (pr.getReturnLevel() == ReturnLevel.DISTRIBUTOR_TO_SUPER_STOCKIST) {
            Long myId = securityUtils.getScopedDistributorId();
            if (pr.getDistributor() == null || !pr.getDistributor().getId().equals(myId)) {
                throw new ForbiddenException("Only the distributor who filed this return can delete it");
            }
        } else {
            Long myId = securityUtils.isSuperStockist() ? securityUtils.getScopedSuperStockistId() : null;
            if (myId == null || pr.getSuperStockist() == null || !pr.getSuperStockist().getId().equals(myId)) {
                throw new ForbiddenException("Only the Super Stockist who filed this return can delete it");
            }
        }
    }

    // Product Ledger is append-only, so a deletion never erases the
    // original SALES_RETURN/PURCHASE_RETURN rows -- it appends a
    // compensating STOCK_ADJUSTMENT pair that nets them to zero.
    private void writeReversalPair(PurchaseReturn pr,
                                    OwnerType fromType, Distributor fromDistributor, SuperStockist fromSuperStockist,
                                    OwnerType toType, Distributor toDistributor, SuperStockist toSuperStockist) {

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String actor = currentUsernameOrSystem();
        BigDecimal qty = BigDecimal.valueOf(pr.getQuantity());
        String remark = "Reversal — return " + pr.getReturnNumber() + " deleted";

        ProductLedgerService.LedgerBuilder out = new ProductLedgerService.LedgerBuilder();
        out.transactionDateTime = now;
        out.voucherNo = pr.getReturnNumber();
        out.transactionType = LedgerTransactionType.STOCK_ADJUSTMENT;
        out.product = pr.getProduct();
        out.ownerType = fromType;
        out.distributor = fromDistributor;
        out.superStockist = fromSuperStockist;
        out.inQuantity = BigDecimal.ZERO;
        out.outQuantity = qty;
        out.unitCost = pr.getUnitPrice();
        out.performedBy = actor;
        out.remarks = remark;
        productLedgerService.record(out);

        ProductLedgerService.LedgerBuilder in = new ProductLedgerService.LedgerBuilder();
        in.transactionDateTime = now;
        in.voucherNo = pr.getReturnNumber();
        in.transactionType = LedgerTransactionType.STOCK_ADJUSTMENT;
        in.product = pr.getProduct();
        in.ownerType = toType;
        in.distributor = toDistributor;
        in.superStockist = toSuperStockist;
        in.inQuantity = qty;
        in.outQuantity = BigDecimal.ZERO;
        in.unitCost = pr.getUnitPrice();
        in.performedBy = actor;
        in.remarks = remark;
        productLedgerService.record(in);
    }

    /* ==================== internals ==================== */

    /**
     * Turns the structured reason + optional note into the display string
     * stored on `reason`. OTHERS is the only case where the note is
     * mandatory -- without it the return would carry no explanation at all,
     * which is the whole point of choosing OTHERS.
     */
    private String resolveReason(PurchaseReturnRequest request, PurchaseReturn.PurchaseReturnBuilder builder) {
        com.spartan.dms.enums.ReturnReason code = request.getReasonCode();
        String note = request.getReasonNote() != null ? request.getReasonNote().trim() : "";

        if (code == com.spartan.dms.enums.ReturnReason.OTHERS && note.isEmpty()) {
            throw new BadRequestException("Please describe the reason when choosing 'Others'.");
        }

        builder.reasonCode(code).reasonNote(note.isEmpty() ? null : note);

        return switch (code) {
            case DAMAGED -> note.isEmpty() ? "Damaged" : "Damaged — " + note;
            case EXPIRED -> note.isEmpty() ? "Expired" : "Expired — " + note;
            case WRONG_ITEM -> note.isEmpty() ? "Wrong Item" : "Wrong Item — " + note;
            case OTHERS -> note;
        };
    }

    // Quantity is re-checked here even though the DTO has @Min(1): the DTO
    // guard only catches nulls/zero, not a client that skips validation.
    private int validatedQuantity(PurchaseReturnRequest request) {
        Integer qty = request.getQuantity();
        if (qty == null || qty < 1) {
            throw new BadRequestException("Quantity must be at least 1.");
        }
        return qty;
    }

    // Each tier is credited back at the rate THEY were charged, so the
    // return value matches what they actually paid for the goods.
    /**
     * The price this party is credited at, resolved ENTIRELY server-side.
     *
     * Order of precedence (matches how the rest of the system prices a
     * sale to this same party):
     *   1. A per-party custom rate, if an admin has set one for this exact
     *      product + distributor / product + super stockist.
     *   2. The product's default tier rate (distributorPrice / ssPrice).
     *
     * Previously only step 2 was consulted, so any party on a negotiated
     * custom rate was credited the wrong amount on every return -- the
     * return never matched what they had actually been charged.
     *
     * NOTE ON MRP: a Product in this schema carries ONE mrp with ONE
     * ssPrice and ONE distributorPrice; there is no per-MRP price-variant
     * table. So MRP identifies the product's retail price, it does not
     * select between several price rows. See the report for detail.
     */
    private BigDecimal resolveRolePrice(Product product, ReturnLevel level,
                                         Distributor distributor, SuperStockist superStockist) {
        BigDecimal price;
        if (level == ReturnLevel.SUPER_STOCKIST_TO_COMPANY) {
            price = superStockist == null ? null : productSuperStockistPriceRepository
                    .findByProductIdAndSuperStockistId(product.getId(), superStockist.getId())
                    .map(com.spartan.dms.entity.ProductSuperStockistPrice::getPrice)
                    .orElse(null);
            if (price == null) {
                price = product.getSsPrice();
            }
        } else {
            price = distributor == null ? null : productDistributorPriceRepository
                    .findByProductIdAndDistributorId(product.getId(), distributor.getId())
                    .map(com.spartan.dms.entity.ProductDistributorPrice::getPrice)
                    .orElse(null);
            if (price == null) {
                price = product.getDistributorPrice();
            }
        }

        if (price == null || price.signum() < 0) {
            throw new BadRequestException("No valid rate is set for '" + product.getProductName()
                    + "' at your level yet — ask an admin to set it before returning this product.");
        }
        return price;
    }

    // Matching OUT (sender) / IN (receiver) pair at the two real locations,
    // so each party's running Product Ledger balance stays reconciled with
    // their own actual on-hand stock.
    private void writeLedgerPair(PurchaseReturn pr,
                                  OwnerType fromType, Distributor fromDistributor, SuperStockist fromSuperStockist,
                                  OwnerType toType, Distributor toDistributor, SuperStockist toSuperStockist) {

        java.time.LocalDateTime when = pr.getReturnDate().atStartOfDay();
        String actor = currentUsernameOrSystem();
        BigDecimal qty = BigDecimal.valueOf(pr.getQuantity());

        ProductLedgerService.LedgerBuilder out = new ProductLedgerService.LedgerBuilder();
        out.transactionDateTime = when;
        out.voucherNo = pr.getReturnNumber();
        out.transactionType = LedgerTransactionType.PURCHASE_RETURN;
        out.product = pr.getProduct();
        out.ownerType = fromType;
        out.distributor = fromDistributor;
        out.superStockist = fromSuperStockist;
        out.inQuantity = BigDecimal.ZERO;
        out.outQuantity = qty;
        out.unitCost = pr.getUnitPrice();
        out.performedBy = actor;
        out.remarks = "Purchase return " + pr.getReturnNumber()
                + (pr.getReason() != null ? " — " + pr.getReason() : "");
        productLedgerService.record(out);

        ProductLedgerService.LedgerBuilder in = new ProductLedgerService.LedgerBuilder();
        in.transactionDateTime = when;
        in.voucherNo = pr.getReturnNumber();
        in.transactionType = LedgerTransactionType.SALES_RETURN;
        in.product = pr.getProduct();
        in.ownerType = toType;
        in.distributor = toDistributor;
        in.superStockist = toSuperStockist;
        in.inQuantity = qty;
        in.outQuantity = BigDecimal.ZERO;
        in.unitCost = pr.getUnitPrice();
        in.performedBy = actor;
        in.remarks = "Sales return received " + pr.getReturnNumber()
                + (pr.getReason() != null ? " — " + pr.getReason() : "");
        productLedgerService.record(in);
    }

    private void creditWarehouse(OwnerType ownerType, SuperStockist superStockist,
                                  Distributor distributor, Product product, int qty) {
        Warehouse warehouse;
        if (ownerType == OwnerType.SUPER_STOCKIST) {
            warehouse = warehouseRepository.findBySuperStockistIdAndProductId(superStockist.getId(), product.getId())
                    .orElseGet(() -> Warehouse.builder()
                            .ownerType(ownerType).superStockist(superStockist).product(product).quantity(0).build());
        } else {
            warehouse = warehouseRepository.findByDistributorIdAndProductId(distributor.getId(), product.getId())
                    .orElseGet(() -> Warehouse.builder()
                            .ownerType(ownerType).distributor(distributor).product(product).quantity(0).build());
        }
        warehouse.setQuantity(warehouse.getQuantity() + qty);
        warehouseRepository.save(warehouse);
    }

    private String currentUsernameOrSystem() {
        try {
            return securityUtils.getCurrentUser().getUsername();
        } catch (RuntimeException ex) {
            return "SYSTEM";
        }
    }

    private ApiResponse<List<PurchaseReturnResponse>> wrap(List<PurchaseReturn> rows, String message) {
        return ApiResponse.<List<PurchaseReturnResponse>>builder()
                .success(true)
                .message(message)
                .data(purchaseReturnMapper.toResponseList(rows))
                .build();
    }

    // Delegates to PurchaseReturnMapper -- kept as a thin private helper so
    // the many call sites above read cleanly.
    private PurchaseReturnResponse toResponse(PurchaseReturn pr) {
        return purchaseReturnMapper.toResponse(pr);
    }
}
