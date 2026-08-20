package com.spartan.dms.service;

import com.spartan.dms.dto.BookEntryResponse;
import com.spartan.dms.dto.BookResponse;
import com.spartan.dms.entity.Invoice;
import com.spartan.dms.entity.Payment;
import com.spartan.dms.entity.SalesReturn;
import com.spartan.dms.repository.InvoiceRepository;
import com.spartan.dms.repository.PaymentRepository;
import com.spartan.dms.repository.SalesReturnRepository;
import com.spartan.dms.util.ExcelGenerator;
import com.spartan.dms.util.PdfGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Cash Book, Bank Book, and Day Book are all read-only *views* over the
 * same source data as Account Ledger (Invoice = Sale, Payment = Receipt) —
 * grouped differently:
 *   - Cash Book:  Payments with paymentMethod = CASH, running cash-in-hand balance
 *   - Bank Book:  Payments with paymentMethod in {BANK, UPI, CHEQUE, CARD}, running bank balance
 *   - Day Book:   every Sale + every Receipt across ALL parties for a date, one flat journal
 *
 * Honest limitation: this app has no expense/purchase-payment recording yet
 * (no Purchase module), so every Cash/Bank Book entry today is a Receipt —
 * the Payment/Credit column exists for when that's added, but is always
 * empty right now. Said plainly in the UI instead of implying it's there.
 */
@Service
@RequiredArgsConstructor
public class CashBankDayBookService {

    private static final List<String> BANK_METHODS = List.of("BANK", "UPI", "CHEQUE", "CARD");

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final SalesReturnRepository salesReturnRepository;
    private final PdfGenerator pdfGenerator;
    private final ExcelGenerator excelGenerator;

    public BookResponse getCashBook(LocalDate fromDate, LocalDate toDate, String search, int page, int size) {
        return buildPaymentBook("CASH", List.of("CASH"), fromDate, toDate, search, page, size);
    }

    public BookResponse getBankBook(LocalDate fromDate, LocalDate toDate, String search, int page, int size) {
        return buildPaymentBook("BANK", BANK_METHODS, fromDate, toDate, search, page, size);
    }

    private BookResponse buildPaymentBook(String bookType, List<String> methods,
                                           LocalDate fromDate, LocalDate toDate, String search, int page, int size) {
        if (fromDate == null) fromDate = LocalDate.now().withDayOfMonth(1);
        if (toDate == null) toDate = LocalDate.now();

        List<Payment> all = paymentRepository.findByPaymentMethodInOrderByPaymentDateAsc(methods);

        BigDecimal runningBalance = BigDecimal.ZERO;
        BigDecimal openingBalance = BigDecimal.ZERO;
        boolean openingComputed = false;

        List<BookEntryResponse> entries = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (Payment p : all) {
            LocalDate d = p.getPaymentDate() != null ? p.getPaymentDate() : LocalDate.now();
            BigDecimal amount = p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO;

            if (d.isBefore(fromDate)) {
                runningBalance = runningBalance.add(amount);
                continue;
            }
            if (!openingComputed) {
                openingBalance = runningBalance;
                openingComputed = true;
            }
            if (d.isAfter(toDate)) continue;

            runningBalance = runningBalance.add(amount);
            totalDebit = totalDebit.add(amount);

            String partyName = p.getShop() != null ? p.getShop().getShopName()
                    : (p.getDistributor() != null ? p.getDistributor().getDistributorName() : "-");

            entries.add(BookEntryResponse.builder()
                    .date(d)
                    .voucherType("Rcpt")
                    .voucherNo("PAY-" + p.getId())
                    .voucherId(p.getId())
                    .partyName(partyName)
                    .paymentMethod(p.getPaymentMethod())
                    .debit(amount)
                    .credit(null)
                    .runningBalance(runningBalance)
                    .build());
        }
        if (!openingComputed) openingBalance = runningBalance;

        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            entries = entries.stream().filter(e ->
                    (e.getPartyName() != null && e.getPartyName().toLowerCase().contains(q)) ||
                    (e.getVoucherNo() != null && e.getVoucherNo().toLowerCase().contains(q))
            ).collect(Collectors.toList());
        }

        return paginate(bookType, fromDate, toDate, openingBalance, entries, totalDebit, totalCredit, runningBalance, page, size);
    }

    public BookResponse getDayBook(LocalDate fromDate, LocalDate toDate, String search, int page, int size) {
        if (fromDate == null) fromDate = LocalDate.now();
        if (toDate == null) toDate = LocalDate.now();

        List<BookEntryResponse> entries = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (Invoice inv : invoiceRepository.findAllOrderByInvoiceDateAsc()) {
            LocalDate d = inv.getInvoiceDate();
            if (d == null || d.isBefore(fromDate) || d.isAfter(toDate)) continue;

            String partyName = inv.getShop() != null ? inv.getShop().getShopName()
                    : (inv.getDistributor() != null ? inv.getDistributor().getDistributorName()
                    : (inv.getSuperStockist() != null ? inv.getSuperStockist().getSuperStockistName() : "-"));
            BigDecimal amount = inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO;

            entries.add(BookEntryResponse.builder()
                    .date(d).voucherType("Sale").voucherNo(inv.getInvoiceNumber()).voucherId(inv.getId())
                    .partyName(partyName).paymentMethod(null)
                    .debit(amount).credit(null).runningBalance(null)
                    .build());
            totalDebit = totalDebit.add(amount);
        }

        for (Payment p : paymentRepository.findAllOrderByPaymentDateAsc()) {
            LocalDate d = p.getPaymentDate();
            if (d == null || d.isBefore(fromDate) || d.isAfter(toDate)) continue;

            String partyName = p.getShop() != null ? p.getShop().getShopName()
                    : (p.getDistributor() != null ? p.getDistributor().getDistributorName() : "-");
            BigDecimal amount = p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO;

            entries.add(BookEntryResponse.builder()
                    .date(d).voucherType("Rcpt").voucherNo("PAY-" + p.getId()).voucherId(p.getId())
                    .partyName(partyName).paymentMethod(p.getPaymentMethod())
                    .debit(null).credit(amount).runningBalance(null)
                    .build());
            totalCredit = totalCredit.add(amount);
        }

        for (SalesReturn sr : salesReturnRepository.findAllOrderByReturnDateAsc()) {
            LocalDate d = sr.getReturnDate();
            if (d == null || d.isBefore(fromDate) || d.isAfter(toDate)) continue;

            String partyName = sr.getShop() != null ? sr.getShop().getShopName() : "-";
            BigDecimal amount = sr.getReturnAmount() != null ? sr.getReturnAmount() : BigDecimal.ZERO;

            entries.add(BookEntryResponse.builder()
                    .date(d).voucherType("SRet").voucherNo("SR-" + sr.getId()).voucherId(sr.getId())
                    .partyName(partyName).paymentMethod(null)
                    .debit(null).credit(amount).runningBalance(null)
                    .build());
            totalCredit = totalCredit.add(amount);
        }

        entries.sort(java.util.Comparator.comparing(BookEntryResponse::getDate)
                .thenComparing(BookEntryResponse::getVoucherType));

        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            entries = entries.stream().filter(e ->
                    (e.getPartyName() != null && e.getPartyName().toLowerCase().contains(q)) ||
                    (e.getVoucherNo() != null && e.getVoucherNo().toLowerCase().contains(q))
            ).collect(Collectors.toList());
        }

        return paginate("DAY", fromDate, toDate, null, entries, totalDebit, totalCredit, null, page, size);
    }

    private BookResponse paginate(String bookType, LocalDate fromDate, LocalDate toDate,
                                   BigDecimal openingBalance, List<BookEntryResponse> entries,
                                   BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal closingBalance,
                                   int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 25 : size;
        int from = Math.min(safePage * safeSize, entries.size());
        int to = Math.min(from + safeSize, entries.size());
        int totalPages = Math.max((int) Math.ceil(entries.size() / (double) safeSize), 1);

        return BookResponse.builder()
                .bookType(bookType)
                .fromDate(fromDate)
                .toDate(toDate)
                .openingBalance(openingBalance)
                .entries(entries.subList(from, to))
                .totalDebit(totalDebit)
                .totalCredit(totalCredit)
                .closingBalance(closingBalance)
                .page(safePage)
                .size(safeSize)
                .totalElements(entries.size())
                .totalPages(totalPages)
                .build();
    }

    /* ---------- Export (all entries, not just current page) ---------- */

    public byte[] exportExcel(String bookType, LocalDate fromDate, LocalDate toDate, String search) throws IOException {
        BookResponse book = fullBook(bookType, fromDate, toDate, search);
        String[] headers = {"Date", "Type", "Vch No", "Party", "Method", "Debit", "Credit", "Balance"};
        List<String[]> rows = book.getEntries().stream().map(this::toCells).collect(Collectors.toList());
        return excelGenerator.generateGenericExcel(bookType + " Book", headers, rows);
    }

    public byte[] exportCsv(String bookType, LocalDate fromDate, LocalDate toDate, String search) {
        BookResponse book = fullBook(bookType, fromDate, toDate, search);
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("Date,Type,Vch No,Party,Method,Debit,Credit,Balance\n");
        for (BookEntryResponse e : book.getEntries()) {
            String[] cells = toCells(e);
            for (String c : cells) sb.append('"').append(c == null ? "" : c.replace("\"", "\"\"")).append("\",");
            sb.setLength(sb.length() - 1);
            sb.append("\n");
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] exportPdf(String bookType, LocalDate fromDate, LocalDate toDate, String search) {
        BookResponse book = fullBook(bookType, fromDate, toDate, search);
        String[] columnHeaders = {"Date", "Type", "Vch No", "Party", "Method", "Debit", "Credit", "Balance"};
        List<String[]> rows = book.getEntries().stream().map(this::toCells).collect(Collectors.toList());
        String[] totalRow = {"", "", "", "", "TOTAL", money(book.getTotalDebit()), money(book.getTotalCredit()),
                book.getClosingBalance() != null ? money(book.getClosingBalance()) : ""};
        String title = bookType.equals("CASH") ? "Cash Book" : bookType.equals("BANK") ? "Bank Book" : "Day Book";
        return pdfGenerator.generateReportTablePdf(title, book.getFromDate() + " to " + book.getToDate(), null, null, columnHeaders, rows, totalRow);
    }

    private BookResponse fullBook(String bookType, LocalDate fromDate, LocalDate toDate, String search) {
        return switch (bookType) {
            case "CASH" -> getCashBook(fromDate, toDate, search, 0, Integer.MAX_VALUE);
            case "BANK" -> getBankBook(fromDate, toDate, search, 0, Integer.MAX_VALUE);
            default -> getDayBook(fromDate, toDate, search, 0, Integer.MAX_VALUE);
        };
    }

    private String[] toCells(BookEntryResponse e) {
        return new String[]{
                String.valueOf(e.getDate()), e.getVoucherType(), e.getVoucherNo(), e.getPartyName(),
                e.getPaymentMethod(), e.getDebit() != null ? money(e.getDebit()) : "",
                e.getCredit() != null ? money(e.getCredit()) : "",
                e.getRunningBalance() != null ? money(e.getRunningBalance()) : ""
        };
    }

    private String money(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
