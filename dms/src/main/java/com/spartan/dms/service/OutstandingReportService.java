package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.OutstandingDetailResponse;
import com.spartan.dms.dto.PagedResponse;
import com.spartan.dms.entity.Invoice;
import com.spartan.dms.repository.InvoiceItemRepository;
import com.spartan.dms.repository.InvoiceRepository;
import com.spartan.dms.util.ExcelGenerator;
import com.spartan.dms.util.PdfGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OutstandingReportService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final PdfGenerator pdfGenerator;
    private final ExcelGenerator excelGenerator;

    /**
     * Same aggregation the dashboard's Outstanding KPI modal uses (see
     * DashboardService#getOutstandingDetails) — every party (Shop /
     * Distributor / Super Stockist) with a positive invoice balance,
     * grouped with district/contact/product context. Kept here as its
     * own copy so this full report page can add search/filter/sort/
     * pagination/export without reshaping the lightweight dashboard modal.
     */
    private List<OutstandingDetailResponse> computeAll(LocalDate fromDate, LocalDate toDate, String paymentStatus) {
        List<Invoice> unpaid = invoiceRepository.findAllWithOutstandingBalance();

        // Date-range and payment-status filters are applied here, at the
        // per-INVOICE level, before aggregation into per-PARTY rows --
        // OutstandingDetailResponse only carries a party's summed total
        // across all of its pending invoices, so filtering after
        // aggregation would have no invoiceDate/paidAmount left to filter
        // on. findAllWithOutstandingBalance() already implicitly means
        // balance > 0 (so "fully paid" never appears here); paymentStatus
        // further splits that into UNPAID (nothing paid yet) vs
        // PARTIALLY_PAID (some payment recorded but still short), same
        // in-Java filtering style as the search/partyType/district
        // filters below rather than pushing this into the repository query.
        if (fromDate != null) {
            unpaid = unpaid.stream()
                    .filter(inv -> inv.getInvoiceDate() != null && !inv.getInvoiceDate().isBefore(fromDate))
                    .collect(Collectors.toList());
        }
        if (toDate != null) {
            unpaid = unpaid.stream()
                    .filter(inv -> inv.getInvoiceDate() != null && !inv.getInvoiceDate().isAfter(toDate))
                    .collect(Collectors.toList());
        }
        if (paymentStatus != null && !paymentStatus.isBlank()) {
            if ("UNPAID".equalsIgnoreCase(paymentStatus)) {
                unpaid = unpaid.stream()
                        .filter(inv -> inv.getPaidAmount() == null || inv.getPaidAmount().compareTo(BigDecimal.ZERO) <= 0)
                        .collect(Collectors.toList());
            } else if ("PARTIALLY_PAID".equalsIgnoreCase(paymentStatus)) {
                unpaid = unpaid.stream()
                        .filter(inv -> inv.getPaidAmount() != null && inv.getPaidAmount().compareTo(BigDecimal.ZERO) > 0)
                        .collect(Collectors.toList());
            }
        }

        if (unpaid.isEmpty()) return List.of();

        List<Long> invoiceIds = unpaid.stream().map(Invoice::getId).collect(Collectors.toList());
        Map<Long, LinkedHashSet<String>> productsByInvoice = new LinkedHashMap<>();
        for (Object[] row : invoiceItemRepository.findProductNamesByInvoiceIds(invoiceIds)) {
            Long invId = (Long) row[0];
            String productName = (String) row[1];
            productsByInvoice.computeIfAbsent(invId, k -> new LinkedHashSet<>()).add(productName);
        }

        Map<String, OutstandingDetailResponse.OutstandingDetailResponseBuilder> builders = new LinkedHashMap<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> productsByParty = new LinkedHashMap<>();

        for (Invoice inv : unpaid) {
            String key;
            OutstandingDetailResponse.OutstandingDetailResponseBuilder b;

            if (inv.getShop() != null) {
                var s = inv.getShop();
                key = "SHOP:" + s.getId();
                b = OutstandingDetailResponse.builder()
                        .partyId(s.getId()).partyType("SHOP").partyName(s.getShopName())
                        .ownerOrContact(s.getOwnerName()).mobileNumber(s.getMobileNumber())
                        .district(s.getDistrict()).state(s.getState());
            } else if (inv.getDistributor() != null) {
                var d = inv.getDistributor();
                key = "DISTRIBUTOR:" + d.getId();
                b = OutstandingDetailResponse.builder()
                        .partyId(d.getId()).partyType("DISTRIBUTOR").partyName(d.getDistributorName())
                        .ownerOrContact(d.getContactPerson()).mobileNumber(d.getMobileNumber())
                        .district(d.getDistrict()).state(d.getState());
            } else if (inv.getSuperStockist() != null) {
                var ss = inv.getSuperStockist();
                key = "SUPERSTOCKIST:" + ss.getId();
                b = OutstandingDetailResponse.builder()
                        .partyId(ss.getId()).partyType("SUPER_STOCKIST").partyName(ss.getSuperStockistName())
                        .ownerOrContact(ss.getContactPerson()).mobileNumber(ss.getMobileNumber())
                        .district(ss.getDistrict()).state(ss.getState());
            } else {
                continue;
            }

            builders.putIfAbsent(key, b);
            totals.merge(key, inv.getBalanceAmount() != null ? inv.getBalanceAmount() : BigDecimal.ZERO, BigDecimal::add);
            counts.merge(key, 1, Integer::sum);
            productsByParty.computeIfAbsent(key, k -> new LinkedHashSet<>())
                    .addAll(productsByInvoice.getOrDefault(inv.getId(), new LinkedHashSet<>()));
        }

        return builders.keySet().stream()
                .map(key -> builders.get(key)
                        .products(new ArrayList<>(productsByParty.getOrDefault(key, new LinkedHashSet<>())))
                        .pendingInvoiceCount(counts.getOrDefault(key, 0))
                        .totalOutstanding(totals.getOrDefault(key, BigDecimal.ZERO))
                        .build())
                .collect(Collectors.toList());
    }

    private List<OutstandingDetailResponse> filterAndSort(String search, String partyType, String district,
                                                            LocalDate fromDate, LocalDate toDate, String paymentStatus,
                                                            String sortBy, String sortDir) {
        List<OutstandingDetailResponse> all = computeAll(fromDate, toDate, paymentStatus);

        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            all = all.stream().filter(r ->
                    (r.getPartyName() != null && r.getPartyName().toLowerCase().contains(q)) ||
                    (r.getOwnerOrContact() != null && r.getOwnerOrContact().toLowerCase().contains(q)) ||
                    (r.getMobileNumber() != null && r.getMobileNumber().contains(q))
            ).collect(Collectors.toList());
        }
        if (partyType != null && !partyType.isBlank()) {
            all = all.stream().filter(r -> partyType.equalsIgnoreCase(r.getPartyType())).collect(Collectors.toList());
        }
        if (district != null && !district.isBlank()) {
            all = all.stream().filter(r -> district.equalsIgnoreCase(r.getDistrict())).collect(Collectors.toList());
        }

        Comparator<OutstandingDetailResponse> cmp;
        String sb = sortBy != null ? sortBy : "totalOutstanding";
        switch (sb) {
            case "partyName" -> cmp = Comparator.comparing(r -> String.valueOf(r.getPartyName()), String.CASE_INSENSITIVE_ORDER);
            case "district" -> cmp = Comparator.comparing(r -> String.valueOf(r.getDistrict()), String.CASE_INSENSITIVE_ORDER);
            case "pendingInvoiceCount" -> cmp = Comparator.comparing(r -> r.getPendingInvoiceCount() != null ? r.getPendingInvoiceCount() : 0);
            default -> cmp = Comparator.comparing(r -> r.getTotalOutstanding() != null ? r.getTotalOutstanding() : BigDecimal.ZERO);
        }
        if (!"asc".equalsIgnoreCase(sortDir)) cmp = cmp.reversed();
        all.sort(cmp);
        return all;
    }

    public ApiResponse<PagedResponse<OutstandingDetailResponse>> getOutstanding(
            String search, String partyType, String district, LocalDate fromDate, LocalDate toDate,
            String paymentStatus, String sortBy, String sortDir, int page, int size) {

        List<OutstandingDetailResponse> filtered =
                filterAndSort(search, partyType, district, fromDate, toDate, paymentStatus, sortBy, sortDir);

        BigDecimal grandTotal = filtered.stream()
                .map(r -> r.getTotalOutstanding() != null ? r.getTotalOutstanding() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int grandTotalInvoices = filtered.stream().mapToInt(r -> r.getPendingInvoiceCount() != null ? r.getPendingInvoiceCount() : 0).sum();

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 20 : size;
        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        List<OutstandingDetailResponse> pageContent = filtered.subList(from, to);
        int totalPages = (int) Math.ceil(filtered.size() / (double) safeSize);

        PagedResponse<OutstandingDetailResponse> result = PagedResponse.<OutstandingDetailResponse>builder()
                .content(pageContent)
                .page(safePage)
                .size(safeSize)
                .totalElements(filtered.size())
                .totalPages(Math.max(totalPages, 1))
                .totals(PagedResponse.BigDecimalTotals.builder()
                        .grandTotalOutstanding(grandTotal)
                        .grandTotalPendingInvoices(grandTotalInvoices)
                        .build())
                .build();

        return ApiResponse.success("Outstanding report fetched", result);
    }

    public byte[] exportExcel(String search, String partyType, String district, LocalDate fromDate, LocalDate toDate,
                               String paymentStatus, String sortBy, String sortDir) throws IOException {
        List<OutstandingDetailResponse> rows =
                filterAndSort(search, partyType, district, fromDate, toDate, paymentStatus, sortBy, sortDir);
        String[] headers = {"Party", "Type", "Contact", "Mobile", "District", "State", "Products", "Pending Invoices", "Total Outstanding"};
        List<String[]> data = rows.stream().map(this::toCells).collect(Collectors.toList());
        return excelGenerator.generateGenericExcel("Outstanding", headers, data);
    }

    public byte[] exportCsv(String search, String partyType, String district, LocalDate fromDate, LocalDate toDate,
                             String paymentStatus, String sortBy, String sortDir) {
        List<OutstandingDetailResponse> rows =
                filterAndSort(search, partyType, district, fromDate, toDate, paymentStatus, sortBy, sortDir);
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("Party,Type,Contact,Mobile,District,State,Products,Pending Invoices,Total Outstanding\n");
        for (OutstandingDetailResponse r : rows) {
            for (String cell : toCells(r)) {
                sb.append('"').append(cell == null ? "" : cell.replace("\"", "\"\"")).append("\",");
            }
            sb.setLength(sb.length() - 1);
            sb.append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] exportPdf(String search, String partyType, String district, LocalDate fromDate, LocalDate toDate,
                             String paymentStatus, String sortBy, String sortDir) {
        List<OutstandingDetailResponse> rows =
                filterAndSort(search, partyType, district, fromDate, toDate, paymentStatus, sortBy, sortDir);
        String[] columnHeaders = {"Party", "Type", "Contact", "Mobile", "District", "Products", "Inv.", "Outstanding"};
        List<String[]> tableRows = rows.stream().map(r -> new String[]{
                r.getPartyName(), r.getPartyType(), r.getOwnerOrContact(), r.getMobileNumber(),
                r.getDistrict(), r.getProducts() != null ? String.join(", ", r.getProducts()) : "",
                String.valueOf(r.getPendingInvoiceCount()), money(r.getTotalOutstanding())
        }).collect(Collectors.toList());

        BigDecimal grandTotal = rows.stream().map(r -> r.getTotalOutstanding() != null ? r.getTotalOutstanding() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String[] totalRow = {"GRAND TOTAL", "", "", "", "", "", "", money(grandTotal)};

        return pdfGenerator.generateReportTablePdf("Outstanding Report", "As of today", null, null, columnHeaders, tableRows, totalRow);
    }

    private String[] toCells(OutstandingDetailResponse r) {
        return new String[]{
                r.getPartyName(), r.getPartyType(), r.getOwnerOrContact(), r.getMobileNumber(),
                r.getDistrict(), r.getState(),
                r.getProducts() != null ? String.join("; ", r.getProducts()) : "",
                String.valueOf(r.getPendingInvoiceCount() != null ? r.getPendingInvoiceCount() : 0),
                money(r.getTotalOutstanding())
        };
    }

    private String money(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
