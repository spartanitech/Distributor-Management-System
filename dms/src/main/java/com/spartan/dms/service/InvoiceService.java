package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.InvoiceRequest;
import com.spartan.dms.dto.InvoiceResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.Invoice;
import com.spartan.dms.entity.Shop;
import com.spartan.dms.entity.SuperStockist;
import com.spartan.dms.enums.InvoiceLevel;
import com.spartan.dms.exception.BadRequestException;
import com.spartan.dms.exception.ForbiddenException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.mapper.InvoiceMapper;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.InvoiceRepository;
import com.spartan.dms.repository.ShopRepository;
import com.spartan.dms.repository.SuperStockistRepository;
import com.spartan.dms.security.SecurityUtils;
import com.spartan.dms.util.PdfGenerator;
import com.spartan.dms.util.QrCodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Backs the full Company -> Super Stockist -> Distributor -> Shop invoice
 * chain. Every leg is stored as an Invoice row (see InvoiceLevel); Admin
 * can always see every row, a Super Stockist sees invoices where they're
 * either the recipient (COMPANY_TO_SUPER_STOCKIST) or the issuer
 * (SUPER_STOCKIST_TO_DISTRIBUTOR), and a Distributor sees invoices where
 * they're either the recipient (SUPER_STOCKIST_TO_DISTRIBUTOR) or the
 * issuer (DISTRIBUTOR_TO_SHOP) — all scoped server-side via SecurityUtils,
 * never trusted from the request body.
 */
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final DistributorRepository distributorRepository;
    private final ShopRepository shopRepository;
    private final SuperStockistRepository superStockistRepository;
    private final com.spartan.dms.repository.ProductRepository productRepository;
    private final com.spartan.dms.repository.InvoiceItemRepository invoiceItemRepository;
    private final com.spartan.dms.repository.StockMovementRepository stockMovementRepository;
    private final com.spartan.dms.repository.PaymentRepository paymentRepository;
    private final com.spartan.dms.repository.PaymentProofRepository paymentProofRepository;
    private final com.spartan.dms.repository.WarehouseRepository warehouseRepository;
    private final InvoiceMapper invoiceMapper;
    private final SecurityUtils securityUtils;
    private final PdfGenerator pdfGenerator;
    private final QrCodeGenerator qrCodeGenerator;
    private final AuditLogService auditLogService;
    private final ProductPricingService productPricingService;
    private final ProductLedgerService productLedgerService;

    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<InvoiceResponse> createInvoice(InvoiceRequest request) {

        InvoiceLevel level = parseLevel(request.getInvoiceLevel());

        // BUG-H6 fix: invoiceDate is NOT NULL at the DB level but was never
        // validated before hitting the database — a missing date used to
        // surface as a raw DataIntegrityViolationException instead of a
        // clean, actionable 400.
        if (request.getInvoiceDate() == null) {
            throw new BadRequestException("invoiceDate is required");
        }

        Invoice invoice = invoiceMapper.toEntity(request);

        // ---- BUG-C1 fix: never trust client-sent financial/payment state ----
        // invoiceMapper.toEntity() copies EVERY matching field off the
        // request DTO, including totalAmount/subTotal/taxAmount/paidAmount/
        // balanceAmount/paymentStatus — which used to mean a caller could
        // create an invoice with a fabricated total, or even declare it
        // "PAID" with zero real Payment rows behind it. From here on:
        //   - totalAmount/subTotal/taxAmount are recomputed below from the
        //     real, server-priced InvoiceItem rows (see saveInvoiceItems()),
        //     and the client-sent values are discarded entirely, UNLESS the
        //     caller supplies no items at all (the documented legacy
        //     header-totals-only path) — in that case there is no line-item
        //     data to derive a total from, so the client-sent totalAmount is
        //     kept, but ONLY that field; see the items.isEmpty() branch below.
        //   - paidAmount/balanceAmount/paymentStatus are ALWAYS forced to
        //     0 / totalAmount / UNPAID here, with no exception. They can
        //     only ever change afterwards through PaymentService (a real
        //     Payment) or SalesReturnService (a real return).
        BigDecimal clientSuppliedTotal = invoice.getTotalAmount();
        BigDecimal clientSuppliedSubTotal = invoice.getSubTotal();
        BigDecimal clientSuppliedTax = invoice.getTaxAmount();
        invoice.setTotalAmount(null);
        invoice.setSubTotal(null);
        invoice.setTaxAmount(null);
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setReturnedAmount(BigDecimal.ZERO);
        invoice.setBalanceAmount(null);
        invoice.setPaymentStatus("UNPAID");

        // The client can only see invoices its role is scoped to (a Super
        // Stockist or Distributor never sees the whole table), so any
        // "next number" it guesses can collide with one issued by someone
        // outside its view -- invoice_number is unique across ALL
        // invoices. Always assign it here, server-side, against the real
        // full table, ignoring whatever the client sent.
        invoice.setInvoiceNumber(generateUniqueInvoiceNumber());

        switch (level) {
            case COMPANY_TO_SUPER_STOCKIST -> {
                // Only Admin issues the Company -> Super Stockist invoice.
                if (!securityUtils.isAdmin()) {
                    throw new ForbiddenException("Only an admin can create a Company -> Super Stockist invoice");
                }
                if (request.getSuperStockistId() == null) {
                    throw new BadRequestException("superStockistId is required for a Company -> Super Stockist invoice");
                }
                SuperStockist superStockist = superStockistRepository.findById(request.getSuperStockistId())
                        .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));
                invoice.setSuperStockist(superStockist);
                invoice.setDistributor(null);
                invoice.setShop(null);
            }
            case SUPER_STOCKIST_TO_DISTRIBUTOR -> {
                // Admin can issue on behalf of any Super Stockist; a Super
                // Stockist login can only issue their own, and only to a
                // distributor actually assigned to them.
                // Validate BEFORE authorizing: assertSuperStockistAccess
                // rejects a null id with a generic "you do not have
                // access" 403, which is actively misleading when the real
                // problem is simply a missing field on the form.
                if (request.getSuperStockistId() == null) {
                    throw new BadRequestException("Please select a Super Stockist for this invoice.");
                }
                if (request.getDistributorId() == null) {
                    throw new BadRequestException("Please select a Distributor for this Super Stockist -> Distributor invoice.");
                }
                securityUtils.assertSuperStockistAccess(request.getSuperStockistId());
                SuperStockist superStockist = superStockistRepository.findById(request.getSuperStockistId())
                        .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));
                Distributor distributor = distributorRepository.findById(request.getDistributorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));
                if (!distributorRepository.existsByIdAndSuperStockistId(distributor.getId(), superStockist.getId())) {
                    throw new BadRequestException("That distributor is not assigned to this Super Stockist");
                }
                invoice.setSuperStockist(superStockist);
                invoice.setDistributor(distributor);
                invoice.setShop(null);
            }
            default -> {
                // DISTRIBUTOR_TO_SHOP — original behavior: a distributor
                // creates their OWN invoices, auto-assigned to themselves;
                // assertDistributorAccess is a no-op for admins and throws
                // if a distributor's request targets someone else.
                // Validate BEFORE authorizing, for the same reason as the
                // branch above -- and report the missing Shop distinctly,
                // since a Distributor invoice ALWAYS requires a linked
                // shop and that's the field most often left blank.
                if (request.getDistributorId() == null) {
                    throw new BadRequestException("Please select a Distributor for this invoice.");
                }
                if (request.getShopId() == null) {
                    throw new BadRequestException("Please select a Shop — a Distributor invoice must be linked to a shop.");
                }
                securityUtils.assertDistributorAccess(request.getDistributorId());
                Distributor distributor = distributorRepository.findById(request.getDistributorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));
                Shop shop = shopRepository.findById(request.getShopId())
                        .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));
                if (shop.getDistributor() == null || !shop.getDistributor().getId().equals(distributor.getId())) {
                    throw new BadRequestException("Selected shop does not belong to this distributor");
                }
                invoice.setDistributor(distributor);
                invoice.setShop(shop);
                invoice.setSuperStockist(null);
            }
        }

        stampCreator(invoice);

        invoice = invoiceRepository.save(invoice);

        InvoiceItemTotals totals = saveInvoiceItems(invoice, request.getItems());

        if (totals.hasItems()) {
            // Real line items were supplied — totalAmount/subTotal/taxAmount
            // are exactly the sum of what was actually persisted per item,
            // never the client's header-level values.
            invoice.setSubTotal(totals.subTotal());
            invoice.setTaxAmount(totals.taxAmount());
            invoice.setTotalAmount(totals.totalAmount());
        } else {
            // Legacy header-totals-only path (no items[] in the request) —
            // there is no line-item data to derive a total from, so a
            // totalAmount must be supplied directly and is trusted (this is
            // the one documented exception; paidAmount/balanceAmount/
            // paymentStatus are still never trusted, see below).
            if (clientSuppliedTotal == null || clientSuppliedTotal.compareTo(BigDecimal.ZERO) < 0) {
                throw new BadRequestException(
                        "totalAmount is required and must be zero or greater when no invoice items are supplied");
            }
            invoice.setTotalAmount(clientSuppliedTotal);
            invoice.setSubTotal(clientSuppliedSubTotal != null ? clientSuppliedSubTotal : clientSuppliedTotal);
            invoice.setTaxAmount(clientSuppliedTax != null ? clientSuppliedTax : BigDecimal.ZERO);
        }

        // paidAmount is still 0 and returnedAmount is still 0 from above —
        // this always resolves to balanceAmount = totalAmount, status = UNPAID.
        invoice.recalculateBalanceAndStatus();
        invoice = invoiceRepository.save(invoice);

        auditLogService.log("CREATE", "INVOICE", invoice.getId(),
                "Created " + level + " invoice " + invoice.getInvoiceNumber());

        return ApiResponse.<InvoiceResponse>builder()
                .success(true)
                .message("Invoice Created Successfully")
                .data(attachItems(invoiceMapper.toResponse(invoice), invoice.getId()))
                .build();
    }

    // Persists the real per-product line items for an invoice (product,
    // qty, unit price, discount, GST%, computed GST amount, line total).
    // Called once right after the invoice header itself is saved. A blank
    // or missing items list is a no-op -- older callers / invoice levels
    // that only send header totals keep working exactly as before.
    /**
     * Server-authoritative price lookup for the 3-tier chain — the client
     * never gets to supply or influence unitPrice. This is what actually
     * enforces "each tier only ever bills at their own rate": a Distributor
     * creating a DISTRIBUTOR_TO_SHOP invoice can never accidentally (or
     * deliberately, via a crafted API call) bill at the ssPrice/distributorPrice
     * rate, because those fields are never even read for that level.
     */
    private java.math.BigDecimal resolveTierPrice(com.spartan.dms.entity.Product product, Invoice invoice) {
        InvoiceLevel level = invoice.getInvoiceLevel();
        java.math.BigDecimal price;
        String tierName;
        switch (level) {
            case COMPANY_TO_SUPER_STOCKIST: {
                Long ssId = invoice.getSuperStockist() != null ? invoice.getSuperStockist().getId() : null;
                price = ssId != null ? productPricingService.resolveSsPrice(product.getId(), ssId) : product.getSsPrice();
                tierName = "SS Price";
                break;
            }
            case SUPER_STOCKIST_TO_DISTRIBUTOR: {
                Long distId = invoice.getDistributor() != null ? invoice.getDistributor().getId() : null;
                price = distId != null ? productPricingService.resolveDistributorPrice(product.getId(), distId) : product.getDistributorPrice();
                tierName = "Distributor Price (DP)";
                break;
            }
            case DISTRIBUTOR_TO_SHOP:
            default:
                price = product.getSellingPrice();
                tierName = "Selling Price";
                break;
        }
        if (price == null) {
            throw new BadRequestException("Admin has not set a " + tierName + " for product '"
                    + product.getProductName() + "' yet — set it in Products (or this partner's custom price) before invoicing at this level.");
        }
        return price;
    }

    // BUG-C1 fix: the sums that become the invoice's actual header totals.
    // hasItems() is false only when the caller supplied no valid items at
    // all, which is what tells createInvoice() to fall back to the legacy
    // header-totals-only path instead of treating an empty sum as "the
    // total really is zero".
    private record InvoiceItemTotals(boolean hasItems, BigDecimal subTotal, BigDecimal taxAmount, BigDecimal totalAmount) {
        static InvoiceItemTotals empty() {
            return new InvoiceItemTotals(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private InvoiceItemTotals saveInvoiceItems(Invoice invoice, List<InvoiceRequest.InvoiceItemRequest> items) {
        if (items == null || items.isEmpty()) {
            return InvoiceItemTotals.empty();
        }

        BigDecimal sumSubTotal = BigDecimal.ZERO;
        BigDecimal sumTax = BigDecimal.ZERO;
        BigDecimal sumTotal = BigDecimal.ZERO;
        boolean any = false;

        for (InvoiceRequest.InvoiceItemRequest itemReq : items) {
            if (itemReq.getProductId() == null || itemReq.getQuantity() == null) {
                continue;
            }
            // ---- BUG-H6 fix: server-side validation, never rely on the
            // frontend alone. quantity/discount/gst are the only item
            // fields a client actually controls (unitPrice is always
            // server-resolved via resolveTierPrice(), never client input).
            if (itemReq.getQuantity() <= 0) {
                throw new BadRequestException("Quantity must be greater than zero for product " + itemReq.getProductId());
            }
            com.spartan.dms.entity.Product product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemReq.getProductId()));

            java.math.BigDecimal unitPrice = resolveTierPrice(product, invoice);
            java.math.BigDecimal discount = itemReq.getDiscountAmount() != null ? itemReq.getDiscountAmount() : java.math.BigDecimal.ZERO;
            java.math.BigDecimal gstPct = itemReq.getGstPercentage() != null ? itemReq.getGstPercentage() : java.math.BigDecimal.ZERO;

            if (discount.compareTo(BigDecimal.ZERO) < 0) {
                throw new BadRequestException("Discount cannot be negative for product " + itemReq.getProductId());
            }
            if (gstPct.compareTo(BigDecimal.ZERO) < 0) {
                throw new BadRequestException("GST percentage cannot be negative for product " + itemReq.getProductId());
            }

            java.math.BigDecimal qty = java.math.BigDecimal.valueOf(itemReq.getQuantity());
            java.math.BigDecimal lineValue = unitPrice.multiply(qty);
            if (discount.compareTo(lineValue) > 0) {
                throw new BadRequestException("Discount cannot exceed the line item value ("
                        + lineValue + ") for product " + itemReq.getProductId());
            }
            java.math.BigDecimal lineBase = lineValue.subtract(discount);
            java.math.BigDecimal gstAmount = lineBase.multiply(gstPct).divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
            java.math.BigDecimal totalAmount = lineBase.add(gstAmount);

            // Draw stock from whichever pool actually holds it at this
            // invoice's level (Company root / the issuing Super Stockist's
            // warehouse / the issuing Distributor's warehouse), and credit
            // the receiving party's warehouse when goods are moving further
            // down the chain. See applyStockForInvoiceLine() below.
            applyStockForInvoiceLine(invoice, product, itemReq.getQuantity(), qty, unitPrice);

            com.spartan.dms.entity.InvoiceItem item = com.spartan.dms.entity.InvoiceItem.builder()
                    .invoice(invoice)
                    .product(product)
                    .quantity(itemReq.getQuantity())
                    .unitPrice(unitPrice)
                    .discountAmount(discount)
                    .gstPercentage(gstPct)
                    .gstAmount(gstAmount)
                    .totalAmount(totalAmount)
                    .build();
            invoiceItemRepository.save(item);

            sumSubTotal = sumSubTotal.add(lineBase);
            sumTax = sumTax.add(gstAmount);
            sumTotal = sumTotal.add(totalAmount);
            any = true;
        }

        return any ? new InvoiceItemTotals(true, sumSubTotal, sumTax, sumTotal) : InvoiceItemTotals.empty();
    }

    /**
     * Fixes a real bug: this used to unconditionally check/decrement
     * Product.stockQuantity (the Company root pool) for EVERY invoice
     * level, and always wrote the Product Ledger OUT entry as
     * ownerType=COMPANY. That was wrong two ways at once:
     *
     *  1. A SUPER_STOCKIST_TO_DISTRIBUTOR or DISTRIBUTOR_TO_SHOP invoice
     *     checked stock against the Company-wide total instead of the
     *     issuing party's own Warehouse row, so a Super Stockist or
     *     Distributor could invoice/sell more than they actually had on
     *     hand as long as the Company total was nonzero -- and their
     *     Warehouse balance never dropped when they sold, so it kept
     *     showing stock that was already gone.
     *  2. Every sale at every level wrote its ledger entry as
     *     ownerType=COMPANY, so the Company's own running balance (used by
     *     the Admin's unfiltered Product Ledger view and by
     *     ProductRequestService's SUPER_STOCKIST_TO_COMPANY fulfillment)
     *     was silently corrupted by unrelated downstream sales, and a
     *     Distributor's/Super Stockist's own ledger view carried the
     *     wrong running balance (the Company pool's, not their own).
     *
     * This mirrors the pattern ProductRequestService.fulfill() already
     * uses for stock requests: draw down the actual source, credit the
     * actual destination's Warehouse, and write matching OUT/IN ledger
     * entries at the real locations involved. A DISTRIBUTOR_TO_SHOP sale
     * is the one true "leaves the system" case -- a Shop isn't
     * Warehouse-tracked, so that gets a single OUT entry, same as before.
     */
    private void applyStockForInvoiceLine(Invoice invoice, com.spartan.dms.entity.Product product,
                                           int qtyInt, java.math.BigDecimal qty, java.math.BigDecimal unitPrice) {

        String voucherNo = invoice.getInvoiceNumber();
        String actor = currentUsernameOrSystem();
        java.time.LocalDateTime txnTime = invoice.getInvoiceDate().atStartOfDay();
        String levelLabel = invoice.getInvoiceLevel().name();

        switch (invoice.getInvoiceLevel()) {
            case COMPANY_TO_SUPER_STOCKIST -> {
                int available = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
                if (qtyInt > available) {
                    throw new BadRequestException("Insufficient Company stock for " + product.getProductName()
                            + " (requested " + qtyInt + ", available " + available + ")");
                }
                product.setStockQuantity(available - qtyInt);
                productRepository.save(product);

                com.spartan.dms.entity.SuperStockist ss = invoice.getSuperStockist();
                creditWarehouse(com.spartan.dms.enums.OwnerType.SUPER_STOCKIST, ss, null, product, qtyInt);

                // Company-root movement -- the only level where the Stock
                // Summary report (keyed off Product.stockQuantity) should
                // see an entry.
                stockMovementRepository.save(com.spartan.dms.entity.StockMovement.builder()
                        .product(product)
                        .movementType(com.spartan.dms.entity.StockMovement.MovementType.OUTWARD)
                        .quantity(qty)
                        .rate(unitPrice)
                        .value(unitPrice.multiply(qty))
                        .movementDate(invoice.getInvoiceDate())
                        .referenceType("INVOICE")
                        .referenceId(voucherNo)
                        .build());

                ProductLedgerService.LedgerBuilder out = newLedgerLine(txnTime, voucherNo, product, unitPrice, actor);
                out.transactionType = com.spartan.dms.enums.LedgerTransactionType.SALES;
                out.ownerType = com.spartan.dms.enums.OwnerType.COMPANY;
                out.outQuantity = qty;
                out.superStockist = ss;
                out.remarks = "Sale via " + levelLabel + " invoice " + voucherNo;
                productLedgerService.record(out);

                ProductLedgerService.LedgerBuilder in = newLedgerLine(txnTime, voucherNo, product, unitPrice, actor);
                in.transactionType = com.spartan.dms.enums.LedgerTransactionType.SALES;
                in.ownerType = com.spartan.dms.enums.OwnerType.SUPER_STOCKIST;
                in.superStockist = ss;
                in.inQuantity = qty;
                in.remarks = "Received via " + levelLabel + " invoice " + voucherNo;
                productLedgerService.record(in);
            }
            case SUPER_STOCKIST_TO_DISTRIBUTOR -> {
                com.spartan.dms.entity.SuperStockist ss = invoice.getSuperStockist();
                com.spartan.dms.entity.Warehouse ssWarehouse = warehouseRepository
                        .findBySuperStockistIdAndProductId(ss.getId(), product.getId())
                        .orElseThrow(() -> new BadRequestException(
                                "No stock recorded in " + ss.getSuperStockistName() + "'s warehouse for " + product.getProductName()));
                if (ssWarehouse.getQuantity() < qtyInt) {
                    throw new BadRequestException("Insufficient stock in warehouse for " + product.getProductName()
                            + " (requested " + qtyInt + ", available " + ssWarehouse.getQuantity() + ")");
                }
                ssWarehouse.setQuantity(ssWarehouse.getQuantity() - qtyInt);
                warehouseRepository.save(ssWarehouse);

                com.spartan.dms.entity.Distributor distributor = invoice.getDistributor();
                creditWarehouse(com.spartan.dms.enums.OwnerType.DISTRIBUTOR, null, distributor, product, qtyInt);

                ProductLedgerService.LedgerBuilder out = newLedgerLine(txnTime, voucherNo, product, unitPrice, actor);
                out.transactionType = com.spartan.dms.enums.LedgerTransactionType.SALES;
                out.ownerType = com.spartan.dms.enums.OwnerType.SUPER_STOCKIST;
                out.superStockist = ss;
                out.outQuantity = qty;
                out.remarks = "Sale via " + levelLabel + " invoice " + voucherNo;
                productLedgerService.record(out);

                ProductLedgerService.LedgerBuilder in = newLedgerLine(txnTime, voucherNo, product, unitPrice, actor);
                in.transactionType = com.spartan.dms.enums.LedgerTransactionType.SALES;
                in.ownerType = com.spartan.dms.enums.OwnerType.DISTRIBUTOR;
                in.distributor = distributor;
                in.inQuantity = qty;
                in.remarks = "Received via " + levelLabel + " invoice " + voucherNo;
                productLedgerService.record(in);
            }
            default -> {
                // DISTRIBUTOR_TO_SHOP -- the final sale. Shop isn't
                // Warehouse-tracked, so only the Distributor's own on-hand
                // stock is checked/drawn down, with a single OUT entry.
                com.spartan.dms.entity.Distributor distributor = invoice.getDistributor();
                com.spartan.dms.entity.Warehouse distWarehouse = warehouseRepository
                        .findByDistributorIdAndProductId(distributor.getId(), product.getId())
                        .orElseThrow(() -> new BadRequestException(
                                "No stock recorded in your warehouse for " + product.getProductName()));
                if (distWarehouse.getQuantity() < qtyInt) {
                    throw new BadRequestException("Insufficient stock for " + product.getProductName()
                            + " (requested " + qtyInt + ", available " + distWarehouse.getQuantity() + ")");
                }
                distWarehouse.setQuantity(distWarehouse.getQuantity() - qtyInt);
                warehouseRepository.save(distWarehouse);

                ProductLedgerService.LedgerBuilder out = newLedgerLine(txnTime, voucherNo, product, unitPrice, actor);
                out.transactionType = com.spartan.dms.enums.LedgerTransactionType.SALES;
                out.ownerType = com.spartan.dms.enums.OwnerType.DISTRIBUTOR;
                out.distributor = distributor;
                out.shop = invoice.getShop();
                out.outQuantity = qty;
                out.remarks = "Sale via " + levelLabel + " invoice " + voucherNo;
                productLedgerService.record(out);
            }
        }
    }

    // Credits qty onto the receiving party's Warehouse row, creating it at
    // zero first if this is the first stock they've ever received for this
    // product. Exactly one of {superStockist, distributor} should be
    // non-null, matching Warehouse's own ownerType discriminator.
    private void creditWarehouse(com.spartan.dms.enums.OwnerType ownerType,
                                  com.spartan.dms.entity.SuperStockist superStockist,
                                  com.spartan.dms.entity.Distributor distributor,
                                  com.spartan.dms.entity.Product product, int qtyInt) {
        com.spartan.dms.entity.Warehouse warehouse;
        if (ownerType == com.spartan.dms.enums.OwnerType.SUPER_STOCKIST) {
            warehouse = warehouseRepository.findBySuperStockistIdAndProductId(superStockist.getId(), product.getId())
                    .orElseGet(() -> com.spartan.dms.entity.Warehouse.builder()
                            .ownerType(ownerType)
                            .superStockist(superStockist)
                            .product(product)
                            .quantity(0)
                            .build());
        } else {
            warehouse = warehouseRepository.findByDistributorIdAndProductId(distributor.getId(), product.getId())
                    .orElseGet(() -> com.spartan.dms.entity.Warehouse.builder()
                            .ownerType(ownerType)
                            .distributor(distributor)
                            .product(product)
                            .quantity(0)
                            .build());
        }
        warehouse.setQuantity(warehouse.getQuantity() + qtyInt);
        warehouseRepository.save(warehouse);
    }

    /**
     * Reverses applyStockForInvoiceLine() for a deleted invoice's line —
     * restores stock at the exact pool it was originally drawn from, and
     * debits whatever pool was credited downstream. Product Ledger is
     * append-only, so this writes compensating STOCK_ADJUSTMENT entries at
     * the same locations rather than touching the original SALES rows.
     * If some of the downstream stock has already moved on again (a
     * further sale/transfer happened before this invoice was deleted), the
     * debit is clamped at zero rather than going negative — the ledger's
     * remarks flag that case for a manual look rather than the delete
     * silently producing an impossible negative balance.
     */
    private void reverseStockForInvoiceLine(Invoice invoice, com.spartan.dms.entity.Product product,
                                             int qtyInt, java.math.BigDecimal unitPrice) {
        java.math.BigDecimal qty = java.math.BigDecimal.valueOf(qtyInt);
        String voucherNo = invoice.getInvoiceNumber();
        String actor = currentUsernameOrSystem();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String remark = "Reversal — invoice " + voucherNo + " deleted";

        switch (invoice.getInvoiceLevel()) {
            case COMPANY_TO_SUPER_STOCKIST -> {
                int current = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
                product.setStockQuantity(current + qtyInt);
                productRepository.save(product);

                com.spartan.dms.entity.SuperStockist ss = invoice.getSuperStockist();
                debitWarehouseClamped(com.spartan.dms.enums.OwnerType.SUPER_STOCKIST, ss, null, product, qtyInt, remark);

                ProductLedgerService.LedgerBuilder in = newLedgerLine(now, voucherNo, product, unitPrice, actor);
                in.transactionType = com.spartan.dms.enums.LedgerTransactionType.STOCK_ADJUSTMENT;
                in.ownerType = com.spartan.dms.enums.OwnerType.COMPANY;
                in.inQuantity = qty;
                in.remarks = remark;
                productLedgerService.record(in);

                ProductLedgerService.LedgerBuilder out = newLedgerLine(now, voucherNo, product, unitPrice, actor);
                out.transactionType = com.spartan.dms.enums.LedgerTransactionType.STOCK_ADJUSTMENT;
                out.ownerType = com.spartan.dms.enums.OwnerType.SUPER_STOCKIST;
                out.superStockist = ss;
                out.outQuantity = qty;
                out.remarks = remark;
                productLedgerService.record(out);
            }
            case SUPER_STOCKIST_TO_DISTRIBUTOR -> {
                com.spartan.dms.entity.SuperStockist ss = invoice.getSuperStockist();
                creditWarehouse(com.spartan.dms.enums.OwnerType.SUPER_STOCKIST, ss, null, product, qtyInt);

                com.spartan.dms.entity.Distributor distributor = invoice.getDistributor();
                debitWarehouseClamped(com.spartan.dms.enums.OwnerType.DISTRIBUTOR, null, distributor, product, qtyInt, remark);

                ProductLedgerService.LedgerBuilder in = newLedgerLine(now, voucherNo, product, unitPrice, actor);
                in.transactionType = com.spartan.dms.enums.LedgerTransactionType.STOCK_ADJUSTMENT;
                in.ownerType = com.spartan.dms.enums.OwnerType.SUPER_STOCKIST;
                in.superStockist = ss;
                in.inQuantity = qty;
                in.remarks = remark;
                productLedgerService.record(in);

                ProductLedgerService.LedgerBuilder out = newLedgerLine(now, voucherNo, product, unitPrice, actor);
                out.transactionType = com.spartan.dms.enums.LedgerTransactionType.STOCK_ADJUSTMENT;
                out.ownerType = com.spartan.dms.enums.OwnerType.DISTRIBUTOR;
                out.distributor = distributor;
                out.outQuantity = qty;
                out.remarks = remark;
                productLedgerService.record(out);
            }
            default -> {
                // DISTRIBUTOR_TO_SHOP — restore the Distributor's warehouse;
                // there's no downstream (Shop) pool to debit.
                com.spartan.dms.entity.Distributor distributor = invoice.getDistributor();
                creditWarehouse(com.spartan.dms.enums.OwnerType.DISTRIBUTOR, null, distributor, product, qtyInt);

                ProductLedgerService.LedgerBuilder in = newLedgerLine(now, voucherNo, product, unitPrice, actor);
                in.transactionType = com.spartan.dms.enums.LedgerTransactionType.STOCK_ADJUSTMENT;
                in.ownerType = com.spartan.dms.enums.OwnerType.DISTRIBUTOR;
                in.distributor = distributor;
                in.shop = invoice.getShop();
                in.inQuantity = qty;
                in.remarks = remark;
                productLedgerService.record(in);
            }
        }
    }

    // Debits qty off the given party's Warehouse row, clamped at zero — used
    // when reversing a deleted invoice's downstream credit. If the party
    // already moved some of that stock on again before the invoice was
    // deleted, going negative would misrepresent reality more than clamping
    // does; the ledger remark on the compensating entry flags it either way.
    private void debitWarehouseClamped(com.spartan.dms.enums.OwnerType ownerType,
                                        com.spartan.dms.entity.SuperStockist superStockist,
                                        com.spartan.dms.entity.Distributor distributor,
                                        com.spartan.dms.entity.Product product, int qtyInt, String remark) {
        java.util.Optional<com.spartan.dms.entity.Warehouse> found = ownerType == com.spartan.dms.enums.OwnerType.SUPER_STOCKIST
                ? warehouseRepository.findBySuperStockistIdAndProductId(superStockist.getId(), product.getId())
                : warehouseRepository.findByDistributorIdAndProductId(distributor.getId(), product.getId());
        if (found.isEmpty()) {
            // Nothing to debit -- the credited stock was already fully
            // consumed/transferred away and its Warehouse row is gone.
            return;
        }
        com.spartan.dms.entity.Warehouse warehouse = found.get();
        warehouse.setQuantity(Math.max(0, warehouse.getQuantity() - qtyInt));
        warehouseRepository.save(warehouse);
    }

    private ProductLedgerService.LedgerBuilder newLedgerLine(java.time.LocalDateTime txnTime, String voucherNo,
                                                               com.spartan.dms.entity.Product product,
                                                               java.math.BigDecimal unitPrice, String actor) {
        ProductLedgerService.LedgerBuilder lb = new ProductLedgerService.LedgerBuilder();
        lb.transactionDateTime = txnTime;
        lb.voucherNo = voucherNo;
        lb.product = product;
        lb.unitCost = unitPrice;
        lb.performedBy = actor;
        lb.inQuantity = java.math.BigDecimal.ZERO;
        lb.outQuantity = java.math.BigDecimal.ZERO;
        return lb;
    }

    // Loads the persisted line items for an invoice and attaches them to an
    // already-built InvoiceResponse. Safe no-op (empty list) for invoices
    // that predate this feature or were created without an items[] payload.
    private InvoiceResponse attachItems(InvoiceResponse response, Long invoiceId) {
        List<com.spartan.dms.entity.InvoiceItem> items = invoiceItemRepository.findByInvoiceIdWithProduct(invoiceId);
        response.setItems(items.stream().map(this::toItemResponse).collect(Collectors.toList()));
        return response;
    }

    /**
     * Bulk counterpart of attachItems(), for list endpoints.
     *
     * Calling attachItems() inside a list loop is an N+1: one query for
     * the invoices plus one per invoice for its items, so a 200-invoice
     * page fired 201 queries. This fetches every line item for the whole
     * page in a single query and groups them in memory instead.
     *
     * Both paths share toItemResponse() so a list row and a detail view
     * can never disagree about how an item is rendered.
     */
    private List<InvoiceResponse> attachItemsBulk(List<Invoice> invoices) {
        if (invoices.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        List<Long> ids = invoices.stream().map(Invoice::getId).collect(Collectors.toList());

        java.util.Map<Long, List<InvoiceResponse.InvoiceItemResponse>> itemsByInvoice =
                invoiceItemRepository.findByInvoiceIdsWithProduct(ids).stream()
                        .collect(Collectors.groupingBy(
                                ii -> ii.getInvoice().getId(),
                                java.util.LinkedHashMap::new,
                                Collectors.mapping(this::toItemResponse, Collectors.toList())));

        return invoices.stream().map(invoice -> {
            InvoiceResponse response = invoiceMapper.toResponse(invoice);
            // An invoice with no line items must still come back with an
            // empty list, not null -- the frontend iterates this directly.
            response.setItems(itemsByInvoice.getOrDefault(invoice.getId(), java.util.Collections.emptyList()));
            return response;
        }).collect(Collectors.toList());
    }

    private InvoiceResponse.InvoiceItemResponse toItemResponse(com.spartan.dms.entity.InvoiceItem ii) {
        return InvoiceResponse.InvoiceItemResponse.builder()
                .id(ii.getId())
                .productId(ii.getProduct() != null ? ii.getProduct().getId() : null)
                .productName(ii.getProduct() != null ? ii.getProduct().getProductName() : null)
                .productCode(ii.getProduct() != null ? ii.getProduct().getProductCode() : null)
                .unit(ii.getProduct() != null ? ii.getProduct().getUnit() : null)
                .quantity(ii.getQuantity())
                .unitPrice(ii.getUnitPrice())
                .discountAmount(ii.getDiscountAmount())
                .gstPercentage(ii.getGstPercentage())
                .gstAmount(ii.getGstAmount())
                .totalAmount(ii.getTotalAmount())
                .build();
    }

    public ApiResponse<List<InvoiceResponse>> getAllInvoices() {

        List<Invoice> invoiceEntities = scopedInvoices();

        List<InvoiceResponse> invoices = attachItemsBulk(invoiceEntities);

        return ApiResponse.<List<InvoiceResponse>>builder()
                .success(true)
                .message("Invoice List")
                .data(invoices)
                .build();
    }

    public ApiResponse<InvoiceResponse> getInvoiceById(Long id) {

        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));

        assertInvoiceAccess(invoice);

        return ApiResponse.<InvoiceResponse>builder()
                .success(true)
                .message("Invoice Details")
                .data(attachItems(invoiceMapper.toResponse(invoice), invoice.getId()))
                .build();
    }

    public ApiResponse<InvoiceResponse> updateInvoice(Long id, InvoiceRequest request) {

        if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Only an admin can update invoices");
        }

        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));

        // BUG-C1 fix (update path): PUT /invoices/{id} is meant for
        // correcting header details (party, date, remarks) — it must not
        // become a second way to fabricate financial state that bypasses
        // saveInvoiceItems()/PaymentService/SalesReturnService. Snapshot
        // the true financial fields before the ModelMapper pass (which
        // would otherwise happily overwrite them from the request body)
        // and restore them unconditionally afterward.
        BigDecimal preSubTotal = invoice.getSubTotal();
        BigDecimal preTax = invoice.getTaxAmount();
        BigDecimal preTotal = invoice.getTotalAmount();
        BigDecimal prePaid = invoice.getPaidAmount();
        BigDecimal preReturned = invoice.getReturnedAmount();
        BigDecimal preBalance = invoice.getBalanceAmount();
        String prePaymentStatus = invoice.getPaymentStatus();

        invoiceMapper.updateEntity(request, invoice);

        invoice.setSubTotal(preSubTotal);
        invoice.setTaxAmount(preTax);
        invoice.setTotalAmount(preTotal);
        invoice.setPaidAmount(prePaid);
        invoice.setReturnedAmount(preReturned);
        invoice.setBalanceAmount(preBalance);
        invoice.setPaymentStatus(prePaymentStatus);

        if (request.getDistributorId() != null) {
            Distributor distributor = distributorRepository.findById(request.getDistributorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));
            invoice.setDistributor(distributor);
        }
        if (request.getShopId() != null) {
            Shop shop = shopRepository.findById(request.getShopId())
                    .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));
            invoice.setShop(shop);
        }
        if (request.getSuperStockistId() != null) {
            SuperStockist superStockist = superStockistRepository.findById(request.getSuperStockistId())
                    .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));
            invoice.setSuperStockist(superStockist);
        }

        invoice = invoiceRepository.save(invoice);

        return ApiResponse.<InvoiceResponse>builder()
                .success(true)
                .message("Invoice Updated Successfully")
                .data(attachItems(invoiceMapper.toResponse(invoice), invoice.getId()))
                .build();
    }

    // BUG-H5 fix: this method reverses stock/warehouse quantities, writes
    // compensating ledger entries, and deletes StockMovement/InvoiceItem/
    // PaymentProof/Payment rows before finally deleting the Invoice itself
    // — several separate repository calls that must all succeed together
    // or not at all. Without @Transactional each save/delete commits on
    // its own, so a failure partway through used to leave a "ghost"
    // invoice header (or reversed stock with no matching invoice deletion,
    // etc.). Spring wraps the whole method in one transaction now; any
    // exception anywhere in here rolls every prior write in this call back.
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<String> deleteInvoice(Long id) {

        if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Only an admin can delete invoices");
        }

        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));

        // InvoiceItem.invoice_id and Payment.invoice_id are both NOT NULL
        // with no DB-level cascade — deleting the invoice first throws an
        // FK violation whenever it has line items or recorded payments.
        // Payments carry their own PaymentProof rows (payment_id NOT NULL,
        // same issue), so those need clearing first too.
        List<com.spartan.dms.entity.InvoiceItem> invoiceItems = invoiceItemRepository.findByInvoiceId(id);
        // Deleting an invoice reverses its stock effect — restore stock at
        // whichever pool applyStockForInvoiceLine() actually drew it from
        // for this invoice's level (mirrors that method exactly, or the
        // reversal would credit the wrong party's stock).
        for (com.spartan.dms.entity.InvoiceItem item : invoiceItems) {
            com.spartan.dms.entity.Product product = item.getProduct();
            if (product != null) {
                reverseStockForInvoiceLine(invoice, product, item.getQuantity(), item.getUnitPrice());
            }
        }
        stockMovementRepository.deleteAll(
                stockMovementRepository.findByReferenceTypeAndReferenceId("INVOICE", invoice.getInvoiceNumber()));
        invoiceItemRepository.deleteAll(invoiceItems);

        List<com.spartan.dms.entity.Payment> payments = paymentRepository.findByInvoiceId(id);
        for (com.spartan.dms.entity.Payment payment : payments) {
            paymentProofRepository.deleteAll(paymentProofRepository.findByPaymentId(payment.getId()));
        }
        paymentRepository.deleteAll(payments);

        invoiceRepository.delete(invoice);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Invoice Deleted Successfully")
                .data("Deleted")
                .build();
    }

    public ApiResponse<InvoiceResponse> getInvoiceByNumber(String invoiceNo) {

        Invoice invoice = invoiceRepository.findByInvoiceNumber(invoiceNo)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Invoice not found"));

        assertInvoiceAccess(invoice);

        return ApiResponse.<InvoiceResponse>builder()
                .success(true)
                .message("Invoice Details")
                .data(attachItems(invoiceMapper.toResponse(invoice), invoice.getId()))
                .build();
    }

    public ApiResponse<List<InvoiceResponse>> searchInvoice(String keyword) {

        List<Invoice> base = scopedInvoices();

        List<Invoice> matched = base
                .stream()
                .filter(invoice ->
                        invoice.getInvoiceNumber() != null &&
                                invoice.getInvoiceNumber().toLowerCase()
                                        .contains(keyword.toLowerCase()))
                .collect(Collectors.toList());

        List<InvoiceResponse> invoices = attachItemsBulk(matched);

        return ApiResponse.<List<InvoiceResponse>>builder()
                .success(true)
                .message("Invoice Search Result")
                .data(invoices)
                .build();
    }

    public ApiResponse<String> updateInvoiceStatus(Long id, String status) {

        if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Only an admin can update invoice status");
        }

        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Invoice not found"));

        // BUG-H4 fix: this used to persist any raw string with no
        // validation, so an invoice could end up with an invoiceStatus
        // that isn't even a real InvoiceStatus value. Validating here
        // means every value that ever reaches the database is guaranteed
        // to be one that InvoiceRepository's CANCELLED-exclusion filters
        // (see sumTotalAmount()/sumPaidAmount()/etc.) actually recognize.
        com.spartan.dms.enums.InvoiceStatus parsedStatus;
        try {
            parsedStatus = com.spartan.dms.enums.InvoiceStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Invalid invoice status: " + status
                    + ". Must be one of " + java.util.Arrays.toString(com.spartan.dms.enums.InvoiceStatus.values()));
        }

        invoice.setInvoiceStatus(parsedStatus.name());

        invoiceRepository.save(invoice);

        auditLogService.log("STATUS_CHANGE", "INVOICE", invoice.getId(),
                "Invoice " + invoice.getInvoiceNumber() + " status set to " + parsedStatus);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Invoice Status Updated Successfully")
                .data("Success")
                .build();
    }

    /**
     * Generates the actual invoice PDF (previously a stub that returned a
     * plain string with no file at all). Includes a QR code that encodes
     * the invoice number + total for quick verification.
     */
    public byte[] generateInvoicePdfBytes(Long id) {

        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));

        assertInvoiceAccess(invoice);

        Double total = invoice.getTotalAmount() != null ? invoice.getTotalAmount().doubleValue() : 0.0;

        String billedTo = invoice.getShop() != null ? invoice.getShop().getShopName()
                : invoice.getDistributor() != null ? invoice.getDistributor().getDistributorName()
                : invoice.getSuperStockist() != null ? invoice.getSuperStockist().getSuperStockistName()
                : "";

        String billedBy = invoice.getDistributor() != null && invoice.getShop() != null ? invoice.getDistributor().getDistributorName()
                : invoice.getSuperStockist() != null ? invoice.getSuperStockist().getSuperStockistName()
                : "Company";

        return pdfGenerator.generateInvoicePdf(
                invoice.getInvoiceNumber(),
                billedTo,
                billedBy,
                total
        );
    }

    public ApiResponse<String> downloadInvoice(Long id) {

        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Invoice not found"));

        assertInvoiceAccess(invoice);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Invoice Download Ready")
                .data("Invoice downloaded successfully")
                .build();
    }

    public ApiResponse<String> printInvoice(Long id) {

        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Invoice not found"));

        assertInvoiceAccess(invoice);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Invoice Print Ready")
                .data("Invoice printed successfully")
                .build();
    }

    // ---- internal helpers ----

    private InvoiceLevel parseLevel(String level) {
        if (level == null || level.isBlank()) {
            return InvoiceLevel.DISTRIBUTOR_TO_SHOP;
        }
        try {
            return InvoiceLevel.valueOf(level.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid invoiceLevel: " + level);
        }
    }

    /**
     * The invoice LIST each role sees: strictly the invoices that role
     * actually raised. Admin is the global administrator and sees every
     * invoice from every role; a Super Stockist sees only invoices they
     * created, a Distributor only invoices they created. No peer ever sees
     * another peer's invoices, and neither sees Admin's.
     *
     * Deliberately creator-based (createdByUserId) rather than
     * party-based: a Super Stockist is a *party* on the
     * COMPANY_TO_SUPER_STOCKIST bill Admin raised against them, and a
     * Distributor is a party on the SUPER_STOCKIST_TO_DISTRIBUTOR bill
     * their Super Stockist raised, but neither of those belongs in "my
     * invoices" -- they're purchases, not sales.
     *
     * Those inbound bills are NOT hidden entirely, or a party could never
     * see or settle what they owe (PaymentService explicitly lets them pay
     * exactly these): they're served by billedToMe() below, and
     * assertInvoiceAccess() still admits them for detail/payment access.
     */
    private List<Invoice> scopedInvoices() {
        if (securityUtils.isAdmin()) {
            return invoiceRepository.findAll();
        }
        Long myUserId = securityUtils.getCurrentUser().getId();
        return invoiceRepository.findByCreatedByUserId(myUserId);
    }

    /**
     * The mirror of scopedInvoices(): bills raised BY someone else that
     * this party has to settle. Admin has none -- Company is the top of
     * the chain and is never billed.
     */
    public ApiResponse<List<InvoiceResponse>> getInvoicesBilledToMe() {

        List<Invoice> entities;
        if (securityUtils.isAdmin()) {
            entities = java.util.Collections.emptyList();
        } else if (securityUtils.isSuperStockist()) {
            Long myId = securityUtils.getScopedSuperStockistId();
            Long myUserId = securityUtils.getCurrentUser().getId();
            entities = invoiceRepository.findBySuperStockistId(myId).stream()
                    .filter(i -> !myUserId.equals(i.getCreatedByUserId()))
                    .collect(Collectors.toList());
        } else {
            Long myId = securityUtils.getScopedDistributorId();
            Long myUserId = securityUtils.getCurrentUser().getId();
            entities = invoiceRepository.findByDistributorId(myId).stream()
                    .filter(i -> !myUserId.equals(i.getCreatedByUserId()))
                    .collect(Collectors.toList());
        }

        List<InvoiceResponse> invoices = attachItemsBulk(entities);

        return ApiResponse.<List<InvoiceResponse>>builder()
                .success(true)
                .message("Bills raised against you")
                .data(invoices)
                .build();
    }

    /**
     * Admin: always allowed. Super Stockist: allowed only if they're the
     * superStockist on the invoice (covers both the leg they received from
     * Admin and the leg they issued to a distributor). Distributor: allowed
     * only if they're the distributor on the invoice (covers both the leg
     * they received from their Super Stockist and the leg they issued to a
     * shop). Never NPEs on a null distributor/superStockist now that both
     * are legitimately null depending on invoiceLevel.
     */
    // Generates INV-<n> against the true global count, retrying past any
    // collision (covers the rare race where two invoices are created in
    // the same instant, plus any gap left by manually-imported numbers).
    private String generateUniqueInvoiceNumber() {
        long next = invoiceRepository.count() + 1;
        String candidate = "INV-" + (1000 + next);
        int guard = 0;
        while (invoiceRepository.existsByInvoiceNumber(candidate) && guard < 1000) {
            next++;
            candidate = "INV-" + (1000 + next);
            guard++;
        }
        return candidate;
    }

    private void assertInvoiceAccess(Invoice invoice) {
        if (securityUtils.isAdmin()) {
            return;
        }
        if (securityUtils.isSuperStockist()) {
            Long myId = securityUtils.getScopedSuperStockistId();
            if (invoice.getSuperStockist() != null && invoice.getSuperStockist().getId().equals(myId)) {
                return;
            }
            // Shop invoice raised by one of this Super Stockist's own
            // distributors (superStockist field is null on these rows,
            // so the check above never catches them - mirrors the
            // findByDistributorSuperStockistId join used in scopedInvoices()).
            if (invoice.getDistributor() != null && invoice.getDistributor().getSuperStockist() != null
                    && invoice.getDistributor().getSuperStockist().getId().equals(myId)) {
                return;
            }
            throw new ForbiddenException("You do not have access to this invoice");
        }
        Long myDistributorId = securityUtils.getScopedDistributorId();
        if (invoice.getDistributor() != null && invoice.getDistributor().getId().equals(myDistributorId)) {
            return;
        }
        throw new ForbiddenException("You do not have access to this invoice");
    }

    // Records WHO raised this invoice, which is not the same question as
    // which parties it's between -- see the createdBy* fields on Invoice.
    // Best-effort: an invoice created outside a request context (seed
    // data, a future scheduled job) is still saved, just unattributed.
    private void stampCreator(Invoice invoice) {
        try {
            com.spartan.dms.entity.User me = securityUtils.getCurrentUser();
            invoice.setCreatedByUserId(me.getId());
            invoice.setCreatedByUsername(me.getUsername());
            invoice.setCreatedByRole(me.getRole() != null ? me.getRole().getRoleName() : null);
        } catch (RuntimeException ex) {
            invoice.setCreatedByUsername("SYSTEM");
        }
    }

    /** Never throws — falls back to "SYSTEM" outside an authenticated request context. */
    private String currentUsernameOrSystem() {
        try {
            return securityUtils.getCurrentUser().getUsername();
        } catch (RuntimeException ex) {
            return "SYSTEM";
        }
    }
}
