package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.SalesReturnRequest;
import com.spartan.dms.dto.SalesReturnResponse;
import com.spartan.dms.dto.SalesReturnSettlementRow;
import com.spartan.dms.entity.Invoice;
import com.spartan.dms.entity.InvoiceItem;
import com.spartan.dms.entity.Product;
import com.spartan.dms.entity.SalesReturn;
import com.spartan.dms.exception.BadRequestException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.mapper.SalesReturnMapper;
import com.spartan.dms.repository.InvoiceItemRepository;
import com.spartan.dms.repository.InvoiceRepository;
import com.spartan.dms.repository.ProductRepository;
import com.spartan.dms.repository.SalesReturnRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A Shop's return against a DISTRIBUTOR_TO_SHOP invoice can be recorded
 * A Shop returning goods (fully or partially) against a specific
 * DISTRIBUTOR_TO_SHOP invoice line item. Returns are ONLY accepted on
 * the 25th of each month — createReturn() rejects any attempt on any
 * other day. The monthly settlement (SalesReturnService.getMonthlySettlement)
 * is simply every return recorded that month, since by construction they
 * can only ever land on the 25th.
 */
@Service
@RequiredArgsConstructor
public class SalesReturnService {

    private final SalesReturnRepository salesReturnRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final ProductRepository productRepository;
    private final SalesReturnMapper salesReturnMapper;
    private final SecurityUtils securityUtils;
    private final com.spartan.dms.repository.StockMovementRepository stockMovementRepository;
    private final com.spartan.dms.repository.WarehouseRepository warehouseRepository;
    private final ProductLedgerService productLedgerService;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final com.spartan.dms.util.PdfGenerator pdfGenerator;

    @Transactional
    public ApiResponse<SalesReturnResponse> createReturn(SalesReturnRequest request) {
        LocalDate today = LocalDate.now();
        if (today.getDayOfMonth() != 25) {
            throw new BadRequestException(
                    "Sales returns can only be recorded on the 25th of each month. Today is "
                            + today + " — please try again on the 25th.");
        }

        // Re-verify the caller's own current password before recording an
        // irreversible reversal of a sale. Checked against the actual
        // logged-in user's own credentials (never the invoice's distributor
        // or anyone else's), so this can't be satisfied with any password
        // other than the one for the session actually making the request.
        com.spartan.dms.entity.User currentUser = securityUtils.getCurrentUser();
        if (!passwordEncoder.matches(request.getConfirmPassword(), currentUser.getPassword())) {
            throw new BadRequestException("Incorrect password. Please re-enter your password to confirm this sales return.");
        }

        Invoice invoice = invoiceRepository.findById(request.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));

        if (invoice.getShop() == null || invoice.getDistributor() == null) {
            throw new BadRequestException("Sales returns can only be recorded against a Distributor→Shop invoice");
        }
        // Distributor can only return against their own invoices; Admin can act for anyone.
        securityUtils.assertDistributorAccess(invoice.getDistributor().getId());

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        // The product being returned must actually have been sold on this
        // invoice — otherwise there's nothing to return it against, and the
        // default-price lookup below would have no line item to read from.
        List<InvoiceItem> items = invoiceItemRepository.findByInvoiceId(invoice.getId());
        InvoiceItem matchingItem = items.stream()
                .filter(it -> it.getProduct() != null && it.getProduct().getId().equals(product.getId()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "Product '" + product.getProductName() + "' was not sold on invoice " + invoice.getInvoiceNumber()));

        if (request.getQuantity() > matchingItem.getQuantity()) {
            throw new BadRequestException("Return quantity (" + request.getQuantity()
                    + ") cannot exceed the quantity originally sold on this invoice (" + matchingItem.getQuantity() + ")");
        }

        BigDecimal unitPrice = request.getUnitPrice() != null ? request.getUnitPrice() : matchingItem.getUnitPrice();
        BigDecimal returnAmount = unitPrice.multiply(BigDecimal.valueOf(request.getQuantity()));

        SalesReturn salesReturn = SalesReturn.builder()
                .invoice(invoice)
                .shop(invoice.getShop())
                .distributor(invoice.getDistributor())
                .product(product)
                .quantity(request.getQuantity())
                .unitPrice(unitPrice)
                .returnAmount(returnAmount)
                .returnDate(today)
                .reason(request.getReason())
                .build();

        salesReturn = salesReturnRepository.save(salesReturn);

        // A return reduces what the shop still owes on this invoice —
        // same formula PaymentService uses for a receipt, just tracked in
        // returnedAmount instead of paidAmount so it's never mistaken for
        // cash actually collected. This is what makes the shop's
        // Outstanding/Customer Ledger balance correct after a return.
        BigDecimal alreadyReturned = invoice.getReturnedAmount() != null ? invoice.getReturnedAmount() : BigDecimal.ZERO;
        BigDecimal newReturned = alreadyReturned.add(returnAmount);
        invoice.setReturnedAmount(newReturned);
        // BUG-H3 fix: recalculateBalanceAndStatus() (see Invoice.java) is
        // now the single formula shared with PaymentService, so a payment
        // recorded/edited/rejected/deleted after this return can never
        // again silently overwrite what this return did to the balance.
        invoice.recalculateBalanceAndStatus();
        invoiceRepository.save(invoice);

        // Returned goods go back into the DISTRIBUTOR's own stock -- they
        // are physically the party receiving them back from the shop, and
        // a DISTRIBUTOR_TO_SHOP sale drew the goods down from that same
        // distributor's warehouse (see InvoiceService.applyStockForInvoiceLine).
        // This previously credited Product.stockQuantity (the COMPANY root
        // pool) instead, which both overstated company stock and left the
        // distributor's own on-hand count short by every unit ever
        // returned to them.
        com.spartan.dms.entity.Distributor returningTo = invoice.getDistributor();
        com.spartan.dms.entity.Warehouse distWarehouse = warehouseRepository
                .findByDistributorIdAndProductId(returningTo.getId(), product.getId())
                .orElseGet(() -> com.spartan.dms.entity.Warehouse.builder()
                        .ownerType(com.spartan.dms.enums.OwnerType.DISTRIBUTOR)
                        .distributor(returningTo)
                        .product(product)
                        .quantity(0)
                        .build());
        distWarehouse.setQuantity(distWarehouse.getQuantity() + request.getQuantity());
        warehouseRepository.save(distWarehouse);

        // Product Ledger -- recorded at the distributor's own location, so
        // their running balance stays reconciled with the warehouse row
        // above. No StockMovement row here: that table feeds the
        // company-level Stock Summary report, which is keyed off
        // Product.stockQuantity, and this leg doesn't change it.
        com.spartan.dms.service.ProductLedgerService.LedgerBuilder lb = new com.spartan.dms.service.ProductLedgerService.LedgerBuilder();
        lb.transactionDateTime = today.atStartOfDay();
        lb.voucherNo = "SR-" + salesReturn.getId();
        lb.transactionType = com.spartan.dms.enums.LedgerTransactionType.SALES_RETURN;
        lb.product = product;
        lb.ownerType = com.spartan.dms.enums.OwnerType.DISTRIBUTOR;
        lb.inQuantity = BigDecimal.valueOf(request.getQuantity());
        lb.outQuantity = BigDecimal.ZERO;
        lb.unitCost = unitPrice;
        lb.shop = invoice.getShop();
        lb.distributor = returningTo;
        lb.performedBy = currentUsernameOrSystem();
        lb.remarks = "Sales return against " + invoice.getInvoiceNumber() + (request.getReason() != null ? " — " + request.getReason() : "");
        productLedgerService.record(lb);

        return ApiResponse.<SalesReturnResponse>builder()
                .success(true)
                .message("Sales return recorded")
                .data(salesReturnMapper.toResponse(salesReturn))
                .build();
    }

    public ApiResponse<SalesReturnResponse> getReturnById(Long id) {
        SalesReturn salesReturn = salesReturnRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sales return not found: " + id));
        if (!securityUtils.isAdmin()) {
            securityUtils.assertDistributorAccess(salesReturn.getDistributor().getId());
        }
        return ApiResponse.<SalesReturnResponse>builder()
                .success(true)
                .data(salesReturnMapper.toResponse(salesReturn))
                .build();
    }

    /**
     * PDF of the sales return register (Shop -> Distributor). Built from
     * getReturns() so the export always matches the caller's own scoped
     * view rather than re-querying with different visibility rules.
     */
    public byte[] exportSalesReturnsPdf() {

        List<SalesReturnResponse> returns = getReturns().getData();

        String[] headers = {"Date", "Invoice", "Shop", "Distributor", "Product", "Qty", "Unit Price", "Amount", "Reason"};
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;

        for (SalesReturnResponse r : returns) {
            rows.add(new String[]{
                    r.getReturnDate() != null ? r.getReturnDate().toString() : "",
                    r.getInvoiceNumber() != null ? r.getInvoiceNumber() : "",
                    r.getShopName() != null ? r.getShopName() : "",
                    r.getDistributorName() != null ? r.getDistributorName() : "",
                    r.getProductName() != null ? r.getProductName() : "",
                    String.valueOf(r.getQuantity()),
                    r.getUnitPrice() != null ? r.getUnitPrice().toPlainString() : "",
                    r.getReturnAmount() != null ? r.getReturnAmount().toPlainString() : "0",
                    r.getReason() != null ? r.getReason() : ""
            });
            if (r.getReturnAmount() != null) total = total.add(r.getReturnAmount());
        }

        String[] totalRow = {"", "", "", "", "", "", "", total.toPlainString(), ""};

        return pdfGenerator.generateReportTablePdf(
                "Sales Return Register",
                "Generated " + java.time.LocalDate.now() + " — " + rows.size() + " return(s)",
                null, null, headers, rows, totalRow);
    }

    public ApiResponse<List<SalesReturnResponse>> getReturns() {
        List<SalesReturn> returns;
        if (securityUtils.isAdmin()) {
            returns = salesReturnRepository.findAllWithDetails();
        } else if (securityUtils.isSuperStockist()) {
            returns = salesReturnRepository.findByDistributorSuperStockistId(securityUtils.getScopedSuperStockistId());
        } else {
            returns = salesReturnRepository.findByDistributorId(securityUtils.getScopedDistributorId());
        }
        List<SalesReturnResponse> data = returns.stream().map(salesReturnMapper::toResponse).collect(Collectors.toList());
        return ApiResponse.<List<SalesReturnResponse>>builder().success(true).data(data).build();
    }

    /**
     * Monthly settlement: sums every return for the given month (every
     * row is guaranteed to be dated the 25th, since createReturn() rejects
     * any other day), grouped by distributor.
     */
    public ApiResponse<Map<String, Object>> getMonthlySettlement(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate cycleStart = ym.atDay(1);
        LocalDate cycleEnd = ym.atEndOfMonth();

        List<SalesReturn> inCycle;
        if (securityUtils.isAdmin()) {
            inCycle = salesReturnRepository.findByReturnDateBetween(cycleStart, cycleEnd);
        } else if (securityUtils.isSuperStockist()) {
            inCycle = salesReturnRepository.findByDistributorSuperStockistId(securityUtils.getScopedSuperStockistId())
                    .stream()
                    .filter(sr -> !sr.getReturnDate().isBefore(cycleStart) && !sr.getReturnDate().isAfter(cycleEnd))
                    .collect(Collectors.toList());
        } else {
            inCycle = salesReturnRepository.findByDistributorIdAndReturnDateBetween(
                    securityUtils.getScopedDistributorId(), cycleStart, cycleEnd);
        }

        Map<Long, SalesReturnSettlementRow> byDistributor = new LinkedHashMap<>();
        for (SalesReturn sr : inCycle) {
            Long distId = sr.getDistributor().getId();
            SalesReturnSettlementRow row = byDistributor.computeIfAbsent(distId, id -> SalesReturnSettlementRow.builder()
                    .distributorId(distId)
                    .distributorName(sr.getDistributor().getDistributorName())
                    .returnCount(0)
                    .totalQuantity(0)
                    .totalReturnAmount(BigDecimal.ZERO)
                    .build());
            row.setReturnCount(row.getReturnCount() + 1);
            row.setTotalQuantity(row.getTotalQuantity() + (sr.getQuantity() != null ? sr.getQuantity() : 0));
            row.setTotalReturnAmount(row.getTotalReturnAmount().add(sr.getReturnAmount() != null ? sr.getReturnAmount() : BigDecimal.ZERO));
        }

        List<SalesReturnSettlementRow> rows = byDistributor.isEmpty() ? Collections.emptyList()
                : new java.util.ArrayList<>(byDistributor.values());
        BigDecimal grandTotal = rows.stream().map(SalesReturnSettlementRow::getTotalReturnAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cycleStart", cycleStart);
        result.put("cycleEnd", cycleEnd);
        result.put("rows", rows);
        result.put("grandTotal", grandTotal);

        return ApiResponse.<Map<String, Object>>builder().success(true).data(result).build();
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
