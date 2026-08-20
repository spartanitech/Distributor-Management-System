package com.spartan.dms.service;

import com.spartan.dms.dto.AccountLedgerResponse;
import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.LedgerEntryResponse;
import com.spartan.dms.dto.LedgerPartyOption;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.Invoice;
import com.spartan.dms.entity.Payment;
import com.spartan.dms.entity.SalesReturn;
import com.spartan.dms.entity.Shop;
import com.spartan.dms.exception.BadRequestException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.InvoiceRepository;
import com.spartan.dms.repository.PaymentRepository;
import com.spartan.dms.repository.SalesReturnRepository;
import com.spartan.dms.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountLedgerService {

    private final com.spartan.dms.security.SecurityUtils securityUtils;
    private final ShopRepository shopRepository;
    private final DistributorRepository distributorRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final SalesReturnRepository salesReturnRepository;
    private final com.spartan.dms.repository.PurchaseReturnRepository purchaseReturnRepository;
    private final com.spartan.dms.util.PdfGenerator pdfGenerator;
    private final com.spartan.dms.util.ExcelGenerator excelGenerator;

    /** Party picker — every Shop and Distributor, for the ledger's "Account" dropdown.
     *  Pass partyTypeFilter ("SHOP" or "DISTRIBUTOR") to restrict it — used by the
     *  Customer Ledger (Shops only) and Supplier Ledger (Distributors only) pages. */
    public ApiResponse<List<LedgerPartyOption>> getParties(String partyTypeFilter) {
        List<LedgerPartyOption> options = new ArrayList<>();

        if (partyTypeFilter == null || "SHOP".equalsIgnoreCase(partyTypeFilter)) {
            shopRepository.findAll().forEach(s -> options.add(LedgerPartyOption.builder()
                    .id(s.getId())
                    .name(s.getShopName())
                    .partyType("SHOP")
                    .subtitle(s.getDistrict())
                    .build()));
        }

        if (partyTypeFilter == null || "DISTRIBUTOR".equalsIgnoreCase(partyTypeFilter)) {
            distributorRepository.findAll().forEach(d -> options.add(LedgerPartyOption.builder()
                    .id(d.getId())
                    .name(d.getDistributorName())
                    .partyType("DISTRIBUTOR")
                    .subtitle(d.getDistrict())
                    .build()));
        }

        options.sort(Comparator.comparing(LedgerPartyOption::getName, String.CASE_INSENSITIVE_ORDER));
        return ApiResponse.success("Ledger parties fetched", options);
    }

    public ApiResponse<AccountLedgerResponse> getLedger(String partyType, Long partyId,
                                                          LocalDate fromDate, LocalDate toDate) {
        return ApiResponse.success("Account ledger fetched", computeLedger(partyType, partyId, fromDate, toDate));
    }

    private AccountLedgerResponse computeLedger(String partyType, Long partyId,
                                                 LocalDate fromDate, LocalDate toDate) {
        // Defence in depth, placed here rather than on getLedger(): the
        // CSV/Excel/PDF exports call computeLedger() directly, so a guard
        // on the read entry point alone would leave three unprotected
        // paths to the same data. This ledger spans every party's
        // financial history, so it is Admin-only.
        //
        // AccountLedgerController already carries a class-level
        // @PreAuthorize("hasRole('ADMIN')"); this ensures any future
        // controller, scheduled job or internal caller inherits the same
        // rule, because the check belongs with the data, not with one
        // entry point.
        securityUtils.assertAdmin();


        if (partyType == null || partyId == null) {
            throw new BadRequestException("partyType and partyId are required");
        }
        if (fromDate == null) fromDate = LocalDate.now().withDayOfMonth(1);
        if (toDate == null) toDate = LocalDate.now();

        String partyName;
        String gstNumber;
        List<Invoice> allInvoices;
        List<Payment> allPayments;
        List<SalesReturn> allReturns = new ArrayList<>();
        List<com.spartan.dms.entity.PurchaseReturn> allPurchaseReturns = new ArrayList<>();

        if ("SHOP".equalsIgnoreCase(partyType)) {
            Shop shop = shopRepository.findById(partyId)
                    .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + partyId));
            partyName = shop.getShopName();
            gstNumber = shop.getGstNumber();
            allInvoices = invoiceRepository.findByShopIdOrderByInvoiceDateAsc(partyId);
            allPayments = paymentRepository.findByShopIdOrderByPaymentDateAsc(partyId);
            // A Sales Return only ever happens against a Distributor->Shop
            // invoice, so it only ever reduces what the SHOP owes — it
            // never appears on the distributor's own ledger.
            allReturns = salesReturnRepository.findByShopIdOrderByReturnDateAsc(partyId);
        } else if ("DISTRIBUTOR".equalsIgnoreCase(partyType)) {
            Distributor distributor = distributorRepository.findById(partyId)
                    .orElseThrow(() -> new ResourceNotFoundException("Distributor not found: " + partyId));
            partyName = distributor.getDistributorName();
            gstNumber = distributor.getGstNumber();
            allInvoices = invoiceRepository.findByDistributorIdOrderByInvoiceDateAsc(partyId);
            allPayments = paymentRepository.findByDistributorIdOrderByPaymentDateAsc(partyId);
            // Goods this distributor sent back UP to their Super Stockist
            // reduce what they owe upstream, so they belong on their
            // account ledger as a credit -- same role a Sales Return plays
            // on a shop's ledger, one level up the chain.
            allPurchaseReturns = purchaseReturnRepository
                    .findByDistributorIdOrderByReturnDateDescIdDesc(partyId);
        } else {
            throw new BadRequestException("partyType must be SHOP or DISTRIBUTOR");
        }

        // Every voucher (Sale = Debit, Receipt = Credit) merged into one
        // chronological list, exactly like Busy/Tally's account ledger.
        record RawVoucher(LocalDate date, Long sortSeq, String type, String voucherNo, Long voucherId,
                           String narration, BigDecimal debit, BigDecimal credit) {}

        List<RawVoucher> vouchers = new ArrayList<>();
        for (Invoice inv : allInvoices) {
            vouchers.add(new RawVoucher(inv.getInvoiceDate(), inv.getId(), "Sale",
                    inv.getInvoiceNumber(), inv.getId(), "Sales",
                    inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO, BigDecimal.ZERO));
        }
        for (Payment pay : allPayments) {
            LocalDate d = pay.getPaymentDate() != null ? pay.getPaymentDate() : LocalDate.now();
            vouchers.add(new RawVoucher(d, pay.getId(), "Rcpt",
                    "PAY-" + pay.getId(), pay.getId(),
                    pay.getPaymentMethod() != null ? pay.getPaymentMethod() : "",
                    BigDecimal.ZERO, pay.getAmount() != null ? pay.getAmount() : BigDecimal.ZERO));
        }
        for (SalesReturn sr : allReturns) {
            LocalDate d = sr.getReturnDate() != null ? sr.getReturnDate() : LocalDate.now();
            vouchers.add(new RawVoucher(d, sr.getId(), "SRet",
                    "SR-" + sr.getId(), sr.getId(),
                    "Return: " + (sr.getProduct() != null ? sr.getProduct().getProductName() : ""),
                    BigDecimal.ZERO, sr.getReturnAmount() != null ? sr.getReturnAmount() : BigDecimal.ZERO));
        }

        for (com.spartan.dms.entity.PurchaseReturn pr : allPurchaseReturns) {
            LocalDate d2 = pr.getReturnDate() != null ? pr.getReturnDate() : LocalDate.now();
            vouchers.add(new RawVoucher(d2, pr.getId(), "PRet",
                    pr.getReturnNumber() != null ? pr.getReturnNumber() : ("PR-" + pr.getId()),
                    pr.getId(),
                    "Purchase Return" + (pr.getReason() != null ? " — " + pr.getReason() : ""),
                    BigDecimal.ZERO,
                    pr.getReturnAmount() != null ? pr.getReturnAmount() : BigDecimal.ZERO));
        }
        vouchers.sort(Comparator.comparing(RawVoucher::date).thenComparing(RawVoucher::sortSeq));

        // Opening balance = net of every voucher strictly before fromDate.
        BigDecimal runningBalance = BigDecimal.ZERO;
        BigDecimal openingBalance = BigDecimal.ZERO;
        boolean openingComputed = false;

        List<LedgerEntryResponse> entries = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (RawVoucher v : vouchers) {
            if (v.date().isBefore(fromDate)) {
                runningBalance = runningBalance.add(v.debit()).subtract(v.credit());
                continue;
            }
            if (!openingComputed) {
                openingBalance = runningBalance;
                openingComputed = true;
            }
            if (v.date().isAfter(toDate)) {
                continue; // outside the report window
            }

            runningBalance = runningBalance.add(v.debit()).subtract(v.credit());
            totalDebit = totalDebit.add(v.debit());
            totalCredit = totalCredit.add(v.credit());

            entries.add(LedgerEntryResponse.builder()
                    .date(v.date())
                    .voucherType(v.type())
                    .voucherNo(v.voucherNo())
                    .voucherId(v.voucherId())
                    .narration(v.narration())
                    .debit(v.debit().signum() == 0 ? null : v.debit())
                    .credit(v.credit().signum() == 0 ? null : v.credit())
                    .runningBalance(runningBalance.abs())
                    .runningBalanceType(runningBalance.signum() >= 0 ? "Dr" : "Cr")
                    .build());
        }
        if (!openingComputed) {
            openingBalance = runningBalance; // no vouchers landed inside the range at all
        }

        return AccountLedgerResponse.builder()
                .partyType(partyType.toUpperCase())
                .partyId(partyId)
                .partyName(partyName)
                .gstNumber(gstNumber)
                .fromDate(fromDate)
                .toDate(toDate)
                .openingBalance(openingBalance.abs())
                .openingBalanceType(openingBalance.signum() >= 0 ? "Dr" : "Cr")
                .entries(entries)
                .totalDebit(totalDebit)
                .totalCredit(totalCredit)
                .closingBalance(runningBalance.abs())
                .closingBalanceType(runningBalance.signum() >= 0 ? "Dr" : "Cr")
                .build();
    }

    public byte[] exportCsv(String partyType, Long partyId, LocalDate fromDate, LocalDate toDate) {
        AccountLedgerResponse ledger = computeLedger(partyType, partyId, fromDate, toDate);
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("Account: ").append(csv(ledger.getPartyName())).append("\n");
        sb.append("Period: ").append(ledger.getFromDate()).append(" to ").append(ledger.getToDate()).append("\n");
        sb.append("Opening Balance:,").append(money(ledger.getOpeningBalance())).append(",").append(ledger.getOpeningBalanceType()).append("\n\n");
        sb.append("Date,Type,Vch/Bill No,Narration,Debit,Credit,Balance\n");
        for (LedgerEntryResponse e : ledger.getEntries()) {
            sb.append(e.getDate()).append(",")
              .append(csv(e.getVoucherType())).append(",")
              .append(csv(e.getVoucherNo())).append(",")
              .append(csv(e.getNarration())).append(",")
              .append(e.getDebit() != null ? money(e.getDebit()) : "").append(",")
              .append(e.getCredit() != null ? money(e.getCredit()) : "").append(",")
              .append(money(e.getRunningBalance())).append(" ").append(e.getRunningBalanceType()).append("\n");
        }
        sb.append("\nClosing Balance:,").append(money(ledger.getClosingBalance())).append(",").append(ledger.getClosingBalanceType()).append("\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] exportExcel(String partyType, Long partyId, LocalDate fromDate, LocalDate toDate) throws java.io.IOException {
        AccountLedgerResponse ledger = computeLedger(partyType, partyId, fromDate, toDate);
        String[] headers = {"Date", "Type", "Vch/Bill No", "Narration", "Debit", "Credit", "Balance"};
        List<String[]> rows = new java.util.ArrayList<>();
        rows.add(new String[]{"Opening Balance", "", "", "", "", "", money(ledger.getOpeningBalance()) + " " + ledger.getOpeningBalanceType()});
        for (LedgerEntryResponse e : ledger.getEntries()) {
            rows.add(new String[]{
                    String.valueOf(e.getDate()), e.getVoucherType(), e.getVoucherNo(), e.getNarration(),
                    e.getDebit() != null ? money(e.getDebit()) : "",
                    e.getCredit() != null ? money(e.getCredit()) : "",
                    money(e.getRunningBalance()) + " " + e.getRunningBalanceType()
            });
        }
        rows.add(new String[]{"Closing Balance", "", "", "", "", "", money(ledger.getClosingBalance()) + " " + ledger.getClosingBalanceType()});
        return excelGenerator.generateGenericExcel("Account Ledger - " + ledger.getPartyName(), headers, rows);
    }

    public byte[] exportPdf(String partyType, Long partyId, LocalDate fromDate, LocalDate toDate) {
        AccountLedgerResponse ledger = computeLedger(partyType, partyId, fromDate, toDate);
        String[] columnHeaders = {"Date", "Type", "Vch/Bill No", "Narration", "Debit", "Credit", "Balance"};
        List<String[]> rows = new java.util.ArrayList<>();
        for (LedgerEntryResponse e : ledger.getEntries()) {
            rows.add(new String[]{
                    String.valueOf(e.getDate()), e.getVoucherType(), e.getVoucherNo(), e.getNarration(),
                    e.getDebit() != null ? money(e.getDebit()) : "",
                    e.getCredit() != null ? money(e.getCredit()) : "",
                    money(e.getRunningBalance()) + " " + e.getRunningBalanceType()
            });
        }
        String[] totalRow = {"", "", "", "Closing Balance", money(ledger.getTotalDebit()), money(ledger.getTotalCredit()),
                money(ledger.getClosingBalance()) + " " + ledger.getClosingBalanceType()};
        String subtitle = "Account: " + ledger.getPartyName() + "  |  " + ledger.getFromDate() + " to " + ledger.getToDate()
                + "  |  Opening Balance: " + money(ledger.getOpeningBalance()) + " " + ledger.getOpeningBalanceType();
        return pdfGenerator.generateReportTablePdf("Account Ledger", subtitle, null, null, columnHeaders, rows, totalRow);
    }

    private String money(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String csv(String v) {
        return v == null ? "" : "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
