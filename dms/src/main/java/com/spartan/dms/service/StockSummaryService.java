package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.StockMovementAgg;
import com.spartan.dms.dto.StockSummaryResponse;
import com.spartan.dms.dto.StockSummaryRow;
import com.spartan.dms.entity.Product;
import com.spartan.dms.entity.StockMovement;
import com.spartan.dms.repository.ProductRepository;
import com.spartan.dms.repository.StockMovementRepository;
import com.spartan.dms.util.ExcelGenerator;
import com.spartan.dms.util.PdfGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StockSummaryService {

    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final PdfGenerator pdfGenerator;
    private final ExcelGenerator excelGenerator;

    /**
     * Opening Qty at fromDate = current stock minus every movement that
     * happened ON/AFTER fromDate (i.e. roll current stock back to what it
     * was right before the range started). Inward/Outward = movements
     * strictly inside [fromDate, toDate]. Closing = Opening + Inward -
     * Outward, which only equals *current* stock when toDate is today —
     * exactly like a Tally/Busy stock summary for a past date range.
     */
    public StockSummaryResponse buildSummary(LocalDate fromDate, LocalDate toDate,
                                              Long categoryId, Long productId, String search) {

        if (fromDate == null) fromDate = LocalDate.now().withDayOfMonth(1);
        if (toDate == null) toDate = LocalDate.now();

        List<Product> products = productRepository.findAll();
        if (categoryId != null) {
            products.removeIf(p -> p.getCategory() == null || !categoryId.equals(p.getCategory().getId()));
        }
        if (productId != null) {
            products.removeIf(p -> !productId.equals(p.getId()));
        }
        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            products.removeIf(p -> p.getProductName() == null || !p.getProductName().toLowerCase().contains(q));
        }

        // One bulk query for the range itself, one bulk query for
        // "everything from fromDate to today" (used only to compute
        // Opening) — two queries total instead of N-per-product.
        Map<String, StockMovementAgg> inRange = index(stockMovementRepository.findAggregatesBetween(fromDate, toDate));
        Map<String, StockMovementAgg> fromDateToNow = index(
                stockMovementRepository.findAggregatesBetween(fromDate, LocalDate.now()));

        List<StockSummaryRow> rows = new ArrayList<>();
        BigDecimal totalOpeningValue = BigDecimal.ZERO;
        BigDecimal totalInwardValue = BigDecimal.ZERO;
        BigDecimal totalOutwardValue = BigDecimal.ZERO;
        BigDecimal totalClosingValue = BigDecimal.ZERO;

        for (Product p : products) {
            BigDecimal currentQty = p.getStockQuantity() != null ? BigDecimal.valueOf(p.getStockQuantity()) : BigDecimal.ZERO;

            StockMovementAgg inwardToNow = fromDateToNow.get(key(p.getId(), StockMovement.MovementType.INWARD));
            StockMovementAgg outwardToNow = fromDateToNow.get(key(p.getId(), StockMovement.MovementType.OUTWARD));
            BigDecimal netQtyFromDateToNow = qty(inwardToNow).subtract(qty(outwardToNow));
            BigDecimal openingQty = currentQty.subtract(netQtyFromDateToNow);

            StockMovementAgg inwardInRange = inRange.get(key(p.getId(), StockMovement.MovementType.INWARD));
            StockMovementAgg outwardInRange = inRange.get(key(p.getId(), StockMovement.MovementType.OUTWARD));
            BigDecimal inwardQty = qty(inwardInRange);
            BigDecimal inwardValue = value(inwardInRange);
            BigDecimal outwardQty = qty(outwardInRange);
            BigDecimal outwardValue = value(outwardInRange);

            BigDecimal closingQty = openingQty.add(inwardQty).subtract(outwardQty);

            BigDecimal costRate = p.getPurchasePrice() != null ? p.getPurchasePrice() : BigDecimal.ZERO;
            BigDecimal openingValue = openingQty.multiply(costRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal closingValue = closingQty.multiply(costRate).setScale(2, RoundingMode.HALF_UP);

            // Skip products with zero activity across the whole row — an
            // untouched product shouldn't clutter a movement report.
            if (openingQty.signum() == 0 && inwardQty.signum() == 0 && outwardQty.signum() == 0 && closingQty.signum() == 0) {
                continue;
            }

            rows.add(StockSummaryRow.builder()
                    .productId(p.getId())
                    .productName(p.getProductName())
                    .unit(p.getUnit())
                    .openingQty(openingQty)
                    .openingRate(costRate)
                    .openingValue(openingValue)
                    .inwardQty(inwardQty)
                    .inwardRate(weightedRate(inwardValue, inwardQty))
                    .inwardValue(inwardValue.setScale(2, RoundingMode.HALF_UP))
                    .outwardQty(outwardQty)
                    .outwardRate(weightedRate(outwardValue, outwardQty))
                    .outwardValue(outwardValue.setScale(2, RoundingMode.HALF_UP))
                    .closingQty(closingQty)
                    .closingRate(costRate)
                    .closingValue(closingValue)
                    .build());

            totalOpeningValue = totalOpeningValue.add(openingValue);
            totalInwardValue = totalInwardValue.add(inwardValue);
            totalOutwardValue = totalOutwardValue.add(outwardValue);
            totalClosingValue = totalClosingValue.add(closingValue);
        }

        return StockSummaryResponse.builder()
                .fromDate(fromDate)
                .toDate(toDate)
                .rows(rows)
                .grandTotalOpeningValue(totalOpeningValue.setScale(2, RoundingMode.HALF_UP))
                .grandTotalInwardValue(totalInwardValue.setScale(2, RoundingMode.HALF_UP))
                .grandTotalOutwardValue(totalOutwardValue.setScale(2, RoundingMode.HALF_UP))
                .grandTotalClosingValue(totalClosingValue.setScale(2, RoundingMode.HALF_UP))
                .build();
    }

    public ApiResponse<StockSummaryResponse> getStockSummary(LocalDate fromDate, LocalDate toDate,
                                                               Long categoryId, Long productId, String search) {
        return ApiResponse.success("Stock summary fetched", buildSummary(fromDate, toDate, categoryId, productId, search));
    }

    public byte[] exportExcel(LocalDate fromDate, LocalDate toDate, Long categoryId, Long productId, String search) throws IOException {
        StockSummaryResponse summary = buildSummary(fromDate, toDate, categoryId, productId, search);
        String[] headers = {
                "Product", "Unit",
                "Opening Qty", "Opening Rate", "Opening Value",
                "Inward Qty", "Inward Rate", "Inward Value",
                "Outward Qty", "Outward Rate", "Outward Value",
                "Closing Qty", "Closing Rate", "Closing Value"
        };
        List<String[]> rows = new ArrayList<>();
        for (StockSummaryRow r : summary.getRows()) {
            rows.add(toCells(r));
        }
        rows.add(new String[]{
                "GRAND TOTAL", "", "", "", str(summary.getGrandTotalOpeningValue()),
                "", "", str(summary.getGrandTotalInwardValue()),
                "", "", str(summary.getGrandTotalOutwardValue()),
                "", "", str(summary.getGrandTotalClosingValue())
        });
        return excelGenerator.generateGenericExcel("Stock Summary", headers, rows);
    }

    public byte[] exportPdf(LocalDate fromDate, LocalDate toDate, Long categoryId, Long productId, String search) {
        StockSummaryResponse summary = buildSummary(fromDate, toDate, categoryId, productId, search);

        String[] groupHeaders = {"", "", "Opening", "Inward", "Outward", "Closing"};
        int[] groupSpans = {1, 1, 3, 3, 3, 3};
        String[] columnHeaders = {
                "Product", "Unit",
                "Qty", "Rate", "Value",
                "Qty", "Rate", "Value",
                "Qty", "Rate", "Value",
                "Qty", "Rate", "Value"
        };
        List<String[]> rows = new ArrayList<>();
        for (StockSummaryRow r : summary.getRows()) {
            rows.add(toCells(r));
        }
        String[] totalRow = {
                "GRAND TOTAL", "", "", "", str(summary.getGrandTotalOpeningValue()),
                "", "", str(summary.getGrandTotalInwardValue()),
                "", "", str(summary.getGrandTotalOutwardValue()),
                "", "", str(summary.getGrandTotalClosingValue())
        };

        String subtitle = "Stock Summary : " + summary.getFromDate() + " to " + summary.getToDate();
        return pdfGenerator.generateReportTablePdf("Stock Summary Report", subtitle,
                groupHeaders, groupSpans, columnHeaders, rows, totalRow);
    }

    /* ---------- helpers ---------- */

    private String[] toCells(StockSummaryRow r) {
        return new String[]{
                r.getProductName(), r.getUnit() != null ? r.getUnit() : "",
                qtyStr(r.getOpeningQty()), str(r.getOpeningRate()), str(r.getOpeningValue()),
                qtyStr(r.getInwardQty()), str(r.getInwardRate()), str(r.getInwardValue()),
                qtyStr(r.getOutwardQty()), str(r.getOutwardRate()), str(r.getOutwardValue()),
                qtyStr(r.getClosingQty()), str(r.getClosingRate()), str(r.getClosingValue())
        };
    }

    private Map<String, StockMovementAgg> index(List<StockMovementAgg> aggs) {
        Map<String, StockMovementAgg> map = new HashMap<>();
        for (StockMovementAgg a : aggs) {
            map.put(key(a.getProductId(), a.getMovementType()), a);
        }
        return map;
    }

    private String key(Long productId, StockMovement.MovementType type) {
        return productId + ":" + type;
    }

    private BigDecimal qty(StockMovementAgg agg) {
        return agg != null && agg.getTotalQuantity() != null ? agg.getTotalQuantity() : BigDecimal.ZERO;
    }

    private BigDecimal value(StockMovementAgg agg) {
        return agg != null && agg.getTotalValue() != null ? agg.getTotalValue() : BigDecimal.ZERO;
    }

    private BigDecimal weightedRate(BigDecimal value, BigDecimal qty) {
        if (qty == null || qty.signum() == 0) return BigDecimal.ZERO;
        return value.divide(qty, 2, RoundingMode.HALF_UP);
    }

    private String str(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String qtyStr(BigDecimal v) {
        if (v == null) return "0";
        return v.stripTrailingZeros().scale() <= 0
                ? v.setScale(0, RoundingMode.HALF_UP).toPlainString()
                : v.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
