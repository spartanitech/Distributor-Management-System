package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.CategorySalesResponse;
import com.spartan.dms.dto.DashboardResponse;
import com.spartan.dms.dto.GroupedSalesResponse;
import com.spartan.dms.dto.LowStockResponse;
import com.spartan.dms.dto.MonthlySalesResponse;
import com.spartan.dms.dto.PaymentMethodTotal;
import com.spartan.dms.dto.PaymentSummaryResponse;
import com.spartan.dms.dto.RecentInvoiceResponse;
import com.spartan.dms.dto.SalesSummaryResponse;
import com.spartan.dms.dto.TopDistributorResponse;
import com.spartan.dms.dto.TopProductResponse;
import com.spartan.dms.entity.Invoice;
import com.spartan.dms.repository.CategoryRepository;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.InvoiceItemRepository;
import com.spartan.dms.repository.InvoiceRepository;
import com.spartan.dms.repository.PaymentRepository;
import com.spartan.dms.repository.ProductRepository;
import com.spartan.dms.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final DistributorRepository distributorRepository;
    private final com.spartan.dms.repository.SuperStockistRepository superStockistRepository;
    private final com.spartan.dms.repository.UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final PaymentRepository paymentRepository;
    private final com.spartan.dms.repository.SalesReturnRepository salesReturnRepository;

    public ApiResponse<DashboardResponse> getDashboard() {

        LocalDate today = LocalDate.now();
        YearMonth currentMonth = YearMonth.from(today);
        YearMonth previousMonth = currentMonth.minusMonths(1);

        LocalDate currentMonthStart = currentMonth.atDay(1);
        LocalDate currentMonthEnd = currentMonth.atEndOfMonth();
        LocalDate previousMonthStart = previousMonth.atDay(1);
        LocalDate previousMonthEnd = previousMonth.atEndOfMonth();

        LocalDateTime currentMonthStartDT = currentMonthStart.atStartOfDay();
        LocalDateTime currentMonthEndDT = currentMonthEnd.plusDays(1).atStartOfDay();
        LocalDateTime previousMonthStartDT = previousMonthStart.atStartOfDay();
        LocalDateTime previousMonthEndDT = previousMonthEnd.plusDays(1).atStartOfDay();

        // Revenue trend: this month vs last month total sales
        BigDecimal currentMonthSales = invoiceRepository.sumTotalAmountBetween(currentMonthStart, currentMonthEnd);
        BigDecimal previousMonthSales = invoiceRepository.sumTotalAmountBetween(previousMonthStart, previousMonthEnd);
        double revenueTrend = percentChange(previousMonthSales, currentMonthSales);

        // Invoice count trend
        long currentMonthInvoices = invoiceRepository.countByInvoiceDateBetween(currentMonthStart, currentMonthEnd);
        long previousMonthInvoices = invoiceRepository.countByInvoiceDateBetween(previousMonthStart, previousMonthEnd);
        double invoicesTrend = percentChange(
                BigDecimal.valueOf(previousMonthInvoices), BigDecimal.valueOf(currentMonthInvoices));

        // Distributor growth trend (new distributors onboarded this month vs last month)
        long currentMonthDistributors = distributorRepository.countByCreatedAtBetween(currentMonthStartDT, currentMonthEndDT);
        long previousMonthDistributors = distributorRepository.countByCreatedAtBetween(previousMonthStartDT, previousMonthEndDT);
        double distributorsTrend = percentChange(
                BigDecimal.valueOf(previousMonthDistributors), BigDecimal.valueOf(currentMonthDistributors));

        // Pending payment trend
        BigDecimal currentMonthPending = invoiceRepository.sumBalanceAmountBetween(currentMonthStart, currentMonthEnd);
        BigDecimal previousMonthPending = invoiceRepository.sumBalanceAmountBetween(previousMonthStart, previousMonthEnd);
        double pendingTrend = percentChange(previousMonthPending, currentMonthPending);

        // Yearly summary
        LocalDate yearStart = today.withDayOfYear(1);
        LocalDate yearEnd = today.withDayOfYear(today.lengthOfYear());
        BigDecimal yearlySales = invoiceRepository.sumTotalAmountBetween(yearStart, yearEnd);
        long yearlyOrders = invoiceRepository.countByInvoiceDateBetween(yearStart, yearEnd);

        DashboardResponse response = DashboardResponse.builder()
                .totalUsers(userRepository.count())
                .totalDistributors(distributorRepository.count())
                .totalSuperStockists(superStockistRepository.count())
                .totalShops(shopRepository.count())
                .totalCategories(categoryRepository.count())
                .totalProducts(productRepository.count())
                .totalInvoices(invoiceRepository.count())
                .totalPayments(paymentRepository.count())

                .totalSales(invoiceRepository.sumTotalAmount())
                .totalPaidAmount(invoiceRepository.sumPaidAmount())
                .totalPendingAmount(invoiceRepository.sumPendingAmount())
                .totalPartialPaidAmount(invoiceRepository.sumPartialPaidAmount())

                .paidInvoices(invoiceRepository.countPaidInvoices())
                .unpaidInvoices(invoiceRepository.countUnpaidInvoices())
                .partiallyPaidInvoices(invoiceRepository.countPartiallyPaidInvoices())

                .lowStockProducts(productRepository.countLowStockProducts())
                .outOfStockProducts(productRepository.countOutOfStockProducts())

                .todayOrders(invoiceRepository.countByInvoiceDate(today))
                .todaySales(invoiceRepository.sumTotalAmountByDate(today))

                .monthlyOrders(currentMonthInvoices)
                .monthlySales(currentMonthSales)

                .yearlyOrders(yearlyOrders)
                .yearlySales(yearlySales)

                .revenueTrendPercent(round(revenueTrend))
                .invoicesTrendPercent(round(invoicesTrend))
                .distributorsTrendPercent(round(distributorsTrend))
                .pendingTrendPercent(round(pendingTrend))

                .monthlySalesReturns(salesReturnRepository.countByReturnDateBetween(currentMonthStart, currentMonthEnd))
                .monthlySalesReturnAmount(salesReturnRepository.sumReturnAmountBetween(currentMonthStart, currentMonthEnd))
                .build();

        return ApiResponse.<DashboardResponse>builder()
                .success(true)
                .message("Dashboard Data")
                .data(response)
                .build();
    }

    public ApiResponse<List<RecentInvoiceResponse>> getRecentInvoices() {

        List<RecentInvoiceResponse> invoices = invoiceRepository.findRecentInvoices(PageRequest.of(0, 10))
                .stream()
                .map(inv -> RecentInvoiceResponse.builder()
                        .id(inv.getId())
                        .invoiceNumber(inv.getInvoiceNumber())
                        .shopName(inv.getShop() != null ? inv.getShop().getShopName() : null)
                        .distributorName(inv.getDistributor() != null ? inv.getDistributor().getDistributorName() : null)
                        .invoiceDate(inv.getInvoiceDate())
                        .totalAmount(inv.getTotalAmount())
                        .paidAmount(inv.getPaidAmount())
                        .balanceAmount(inv.getBalanceAmount())
                        .paymentStatus(inv.getPaymentStatus())
                        .paymentMethod(inv.getPaymentMethod())
                        .watermark(inv.getWatermark())
                        .verified(inv.getBalanceAmount() == null || inv.getBalanceAmount().compareTo(BigDecimal.ZERO) <= 0)
                        .createdAt(inv.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return ApiResponse.<List<RecentInvoiceResponse>>builder()
                .success(true)
                .message("Recent Invoices")
                .data(invoices)
                .build();
    }

    public ApiResponse<List<LowStockResponse>> getLowStockProducts() {

        List<LowStockResponse> products = productRepository.findLowStockProducts()
                .stream()
                .map(p -> LowStockResponse.builder()
                        .productId(p.getId())
                        .productName(p.getProductName())
                        .productCode(p.getProductCode())
                        .barcode(p.getBarcode())
                        .categoryName(p.getCategory() != null ? p.getCategory().getCategoryName() : null)
                        .unit(p.getUnit())
                        .currentStock(p.getStockQuantity())
                        .minimumStock(p.getMinimumStock())
                        .purchasePrice(p.getPurchasePrice())
                        .sellingPrice(p.getSellingPrice())
                        .active(p.getActive())
                        .updatedAt(p.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());

        return ApiResponse.<List<LowStockResponse>>builder()
                .success(true)
                .message("Low Stock Products")
                .data(products)
                .build();
    }

    /**
     * @param months 6 or 12 — how many trailing months to include in the monthly sales trend.
     */
    public ApiResponse<SalesSummaryResponse> getSalesSummary(int months) {

        int rangeMonths = (months == 12) ? 12 : 6;

        YearMonth current = YearMonth.from(LocalDate.now());
        YearMonth from = current.minusMonths(rangeMonths - 1L);
        LocalDate fromDate = from.atDay(1);

        List<Invoice> invoices = invoiceRepository.findAllFromDate(fromDate);

        // Pre-fill every month in the range so months with zero invoices still plot as 0
        LinkedHashMap<YearMonth, BigDecimal> salesByMonth = new LinkedHashMap<>();
        LinkedHashMap<YearMonth, Long> ordersByMonth = new LinkedHashMap<>();
        for (int i = 0; i < rangeMonths; i++) {
            YearMonth ym = from.plusMonths(i);
            salesByMonth.put(ym, BigDecimal.ZERO);
            ordersByMonth.put(ym, 0L);
        }

        for (Invoice inv : invoices) {
            if (inv.getInvoiceDate() == null) continue;
            YearMonth ym = YearMonth.from(inv.getInvoiceDate());
            if (!salesByMonth.containsKey(ym)) continue;
            BigDecimal amount = inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO;
            salesByMonth.merge(ym, amount, BigDecimal::add);
            ordersByMonth.merge(ym, 1L, Long::sum);
        }

        List<MonthlySalesResponse> monthlySales = salesByMonth.keySet().stream()
                .map(ym -> MonthlySalesResponse.builder()
                        .month(ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                        .year(ym.getYear())
                        .totalSales(salesByMonth.get(ym))
                        .totalOrders(ordersByMonth.get(ym))
                        .build())
                .collect(Collectors.toList());

        List<CategorySalesResponse> categorySales = invoiceItemRepository.findCategorySales();

        SalesSummaryResponse response = SalesSummaryResponse.builder()
                .monthlySales(monthlySales)
                .categorySales(categorySales)
                .build();

        return ApiResponse.<SalesSummaryResponse>builder()
                .success(true)
                .message("Sales Summary")
                .data(response)
                .build();
    }

    public ApiResponse<PaymentSummaryResponse> getPaymentSummary() {

        List<PaymentMethodTotal> totals = paymentRepository.sumAmountGroupedByMethod();

        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal upi = BigDecimal.ZERO;
        BigDecimal card = BigDecimal.ZERO;
        BigDecimal bank = BigDecimal.ZERO;
        BigDecimal cheque = BigDecimal.ZERO;

        for (PaymentMethodTotal t : totals) {
            String method = t.getPaymentMethod() == null ? "" : t.getPaymentMethod().trim().toUpperCase();
            BigDecimal amount = t.getTotalAmount() != null ? t.getTotalAmount() : BigDecimal.ZERO;

            switch (method) {
                case "CASH":
                    cash = cash.add(amount);
                    break;
                case "UPI":
                case "GPAY":
                case "PHONEPE":
                case "PAYTM":
                    upi = upi.add(amount);
                    break;
                case "CARD":
                    card = card.add(amount);
                    break;
                case "BANK":
                case "BANK_TRANSFER":
                case "NEFT":
                case "RTGS":
                case "IMPS":
                    bank = bank.add(amount);
                    break;
                case "CHEQUE":
                    cheque = cheque.add(amount);
                    break;
                default:
                    // Unrecognized method label — still reflected in totalCollected below
                    break;
            }
        }

        PaymentSummaryResponse response = PaymentSummaryResponse.builder()
                .cashAmount(cash)
                .upiAmount(upi)
                .cardAmount(card)
                .bankAmount(bank)
                .chequeAmount(cheque)
                .totalCollected(paymentRepository.sumAllAmount())
                .build();

        return ApiResponse.<PaymentSummaryResponse>builder()
                .success(true)
                .message("Payment Summary")
                .data(response)
                .build();
    }

    public ApiResponse<List<TopProductResponse>> getTopProducts(int limit) {

        int size = limit > 0 ? limit : 5;

        List<TopProductResponse> topProducts =
                invoiceItemRepository.findTopSellingProducts(PageRequest.of(0, size));

        return ApiResponse.<List<TopProductResponse>>builder()
                .success(true)
                .message("Top Products")
                .data(topProducts)
                .build();
    }

    public ApiResponse<List<TopDistributorResponse>> getTopDistributors(int limit) {

        int size = limit > 0 ? limit : 5;

        List<TopDistributorResponse> topDistributors =
                invoiceRepository.findTopDistributors(PageRequest.of(0, size));

        return ApiResponse.<List<TopDistributorResponse>>builder()
                .success(true)
                .message("Top Distributors")
                .data(topDistributors)
                .build();
    }

    /**
     * Powers the 3 dashboard bar charts (District / Distributor / Super
     * Stockist), all driven off the same underlying end-customer
     * (DISTRIBUTOR_TO_SHOP) invoice set and the same filter set, so
     * "District = Madurai AND Distributor = ABC AND Date = Last Month"
     * behaves identically across all three — filters always AND together.
     *
     * @param groupBy "district" | "state" | "distributor" | "superStockist"
     */
    public ApiResponse<List<GroupedSalesResponse>> getSalesGrouped(
            String groupBy, String district, String state, Long distributorId, Long superStockistId,
            Integer month, Integer year, LocalDate dateFrom, LocalDate dateTo) {

        List<Invoice> invoices = invoiceRepository.findAllShopInvoicesForCharts().stream()
                .filter(i -> district == null || district.isBlank()
                        || (i.getShop() != null && district.equalsIgnoreCase(i.getShop().getDistrict())))
                .filter(i -> state == null || state.isBlank()
                        || (i.getShop() != null && state.equalsIgnoreCase(i.getShop().getState())))
                .filter(i -> distributorId == null
                        || (i.getDistributor() != null && distributorId.equals(i.getDistributor().getId())))
                .filter(i -> superStockistId == null
                        || (i.getDistributor() != null && i.getDistributor().getSuperStockist() != null
                            && superStockistId.equals(i.getDistributor().getSuperStockist().getId())))
                .filter(i -> month == null || i.getInvoiceDate() == null || i.getInvoiceDate().getMonthValue() == month)
                .filter(i -> year == null || i.getInvoiceDate() == null || i.getInvoiceDate().getYear() == year)
                .filter(i -> dateFrom == null || i.getInvoiceDate() == null || !i.getInvoiceDate().isBefore(dateFrom))
                .filter(i -> dateTo == null || i.getInvoiceDate() == null || !i.getInvoiceDate().isAfter(dateTo))
                .collect(Collectors.toList());

        java.util.function.Function<Invoice, String> labelFn;
        java.util.function.Function<Invoice, Long> idFn;
        switch (groupBy == null ? "" : groupBy) {
            case "state":
                labelFn = i -> i.getShop() != null && i.getShop().getState() != null ? i.getShop().getState() : "Unknown";
                idFn = i -> null;
                break;
            case "distributor":
                labelFn = i -> i.getDistributor() != null ? i.getDistributor().getDistributorName() : "Unknown";
                idFn = i -> i.getDistributor() != null ? i.getDistributor().getId() : null;
                break;
            case "superStockist":
                labelFn = i -> i.getDistributor() != null && i.getDistributor().getSuperStockist() != null
                        ? i.getDistributor().getSuperStockist().getSuperStockistName() : "Unassigned";
                idFn = i -> i.getDistributor() != null && i.getDistributor().getSuperStockist() != null
                        ? i.getDistributor().getSuperStockist().getId() : null;
                break;
            case "district":
            default:
                labelFn = i -> i.getShop() != null && i.getShop().getDistrict() != null ? i.getShop().getDistrict() : "Unknown";
                idFn = i -> null;
                break;
        }

        LinkedHashMap<String, GroupedSalesResponse> grouped = new LinkedHashMap<>();
        for (Invoice inv : invoices) {
            String label = labelFn.apply(inv);
            BigDecimal amount = inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO;
            GroupedSalesResponse row = grouped.computeIfAbsent(label, l -> GroupedSalesResponse.builder()
                    .label(l).groupId(idFn.apply(inv)).totalSales(BigDecimal.ZERO).totalOrders(0L).build());
            row.setTotalSales(row.getTotalSales().add(amount));
            row.setTotalOrders(row.getTotalOrders() + 1);
        }

        List<GroupedSalesResponse> result = grouped.values().stream()
                .sorted((a, b) -> b.getTotalSales().compareTo(a.getTotalSales()))
                .collect(Collectors.toList());

        return ApiResponse.<List<GroupedSalesResponse>>builder()
                .success(true).message("Grouped Sales").data(result).build();
    }

    /* ---------- helpers ---------- */

    private double percentChange(BigDecimal previous, BigDecimal current) {
        BigDecimal prev = previous != null ? previous : BigDecimal.ZERO;
        BigDecimal curr = current != null ? current : BigDecimal.ZERO;

        if (prev.compareTo(BigDecimal.ZERO) == 0) {
            return curr.compareTo(BigDecimal.ZERO) == 0 ? 0.0 : 100.0;
        }
        return curr.subtract(prev)
                .divide(prev, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private Double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /* ---------- Outstanding KPI drill-down ---------- */
    // Powers the "Outstanding" card click: who owes money, where they are
    // (district), what they bought, and how much — grouped per shop /
    // distributor / super stockist so it stays a short, readable list
    // instead of a raw invoice dump.

    public ApiResponse<List<com.spartan.dms.dto.OutstandingDetailResponse>> getOutstandingDetails() {
        List<Invoice> unpaid = invoiceRepository.findAllWithOutstandingBalance();

        if (unpaid.isEmpty()) {
            return ApiResponse.success("Outstanding details fetched", List.of());
        }

        List<Long> invoiceIds = unpaid.stream().map(Invoice::getId).collect(Collectors.toList());

        // invoiceId -> distinct product names, built from one bulk query
        java.util.Map<Long, java.util.LinkedHashSet<String>> productsByInvoice = new java.util.HashMap<>();
        for (Object[] row : invoiceItemRepository.findProductNamesByInvoiceIds(invoiceIds)) {
            Long invId = (Long) row[0];
            String productName = (String) row[1];
            productsByInvoice.computeIfAbsent(invId, k -> new java.util.LinkedHashSet<>()).add(productName);
        }

        // Group key: "SHOP:<id>" or "DISTRIBUTOR:<id>" or "SUPERSTOCKIST:<id>"
        java.util.Map<String, com.spartan.dms.dto.OutstandingDetailResponse.OutstandingDetailResponseBuilder> builders = new LinkedHashMap<>();
        java.util.Map<String, BigDecimal> totals = new LinkedHashMap<>();
        java.util.Map<String, Integer> counts = new LinkedHashMap<>();
        java.util.Map<String, java.util.LinkedHashSet<String>> productsByParty = new LinkedHashMap<>();

        for (Invoice inv : unpaid) {
            String key;
            com.spartan.dms.dto.OutstandingDetailResponse.OutstandingDetailResponseBuilder b;

            if (inv.getShop() != null) {
                com.spartan.dms.entity.Shop s = inv.getShop();
                key = "SHOP:" + s.getId();
                b = com.spartan.dms.dto.OutstandingDetailResponse.builder()
                        .partyId(s.getId())
                        .partyType("SHOP")
                        .partyName(s.getShopName())
                        .ownerOrContact(s.getOwnerName())
                        .mobileNumber(s.getMobileNumber())
                        .district(s.getDistrict())
                        .state(s.getState());
            } else if (inv.getDistributor() != null) {
                com.spartan.dms.entity.Distributor d = inv.getDistributor();
                key = "DISTRIBUTOR:" + d.getId();
                b = com.spartan.dms.dto.OutstandingDetailResponse.builder()
                        .partyId(d.getId())
                        .partyType("DISTRIBUTOR")
                        .partyName(d.getDistributorName())
                        .ownerOrContact(d.getContactPerson())
                        .mobileNumber(d.getMobileNumber())
                        .district(d.getDistrict())
                        .state(d.getState());
            } else if (inv.getSuperStockist() != null) {
                com.spartan.dms.entity.SuperStockist ss = inv.getSuperStockist();
                key = "SUPERSTOCKIST:" + ss.getId();
                b = com.spartan.dms.dto.OutstandingDetailResponse.builder()
                        .partyId(ss.getId())
                        .partyType("SUPER_STOCKIST")
                        .partyName(ss.getSuperStockistName())
                        .ownerOrContact(ss.getContactPerson())
                        .mobileNumber(ss.getMobileNumber())
                        .district(ss.getDistrict())
                        .state(ss.getState());
            } else {
                continue; // no identifiable debtor on this invoice — skip rather than guess
            }

            builders.putIfAbsent(key, b);
            totals.merge(key, inv.getBalanceAmount() != null ? inv.getBalanceAmount() : BigDecimal.ZERO, BigDecimal::add);
            counts.merge(key, 1, Integer::sum);
            productsByParty.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>())
                    .addAll(productsByInvoice.getOrDefault(inv.getId(), new java.util.LinkedHashSet<>()));
        }

        List<com.spartan.dms.dto.OutstandingDetailResponse> result = builders.keySet().stream()
                .map(key -> builders.get(key)
                        .products(new java.util.ArrayList<>(productsByParty.getOrDefault(key, new java.util.LinkedHashSet<>())))
                        .pendingInvoiceCount(counts.getOrDefault(key, 0))
                        .totalOutstanding(totals.getOrDefault(key, BigDecimal.ZERO))
                        .build())
                .sorted((a, c) -> c.getTotalOutstanding().compareTo(a.getTotalOutstanding()))
                .collect(Collectors.toList());

        return ApiResponse.success("Outstanding details fetched", result);
    }
}

