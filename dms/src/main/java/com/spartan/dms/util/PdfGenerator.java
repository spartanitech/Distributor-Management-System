package com.spartan.dms.util;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.io.image.ImageDataFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class PdfGenerator {

    private final QrCodeGenerator qrCodeGenerator;

    // Bold/regular weight is applied via an explicit PdfFont (Helvetica /
    // Helvetica-Bold), not Paragraph.setBold() — setBold() simulates a
    // fake "bold" by skewing/thickening the regular glyphs client-side
    // and its availability has moved around across iText7/8/9 layout
    // releases. Loading the real bold font program via PdfFontFactory is
    // the version-stable way to do this and renders actual bold glyphs.
    private PdfFont regularFont() {
        try {
            return PdfFontFactory.createFont(StandardFonts.HELVETICA);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load base PDF font", e);
        }
    }

    private PdfFont boldFont() {
        try {
            return PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load bold PDF font", e);
        }
    }

    public byte[] generateInvoicePdf(String invoiceNumber,
                                     String shopName,
                                     String distributorName,
                                     Double totalAmount) {

        // BUG-L4 fix: without these guards, a null shopName/distributorName/
        // totalAmount (e.g. a COMPANY_TO_SUPER_STOCKIST invoice, which has
        // no shop, or a legacy row missing a total) rendered the literal
        // text "null" into the generated PDF instead of a sane placeholder.
        String safeInvoiceNumber = invoiceNumber != null ? invoiceNumber : "\u2014";
        String safeShopName = shopName != null && !shopName.isBlank() ? shopName : "\u2014";
        String safeDistributorName = distributorName != null && !distributorName.isBlank() ? distributorName : "\u2014";
        String safeTotalAmount = totalAmount != null ? String.format("%,.2f", totalAmount) : "0.00";

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        PdfWriter writer = new PdfWriter(outputStream);
        PdfDocument pdfDocument = new PdfDocument(writer);
        Document document = new Document(pdfDocument);

        document.add(new Paragraph("Distributor Management System"));
        document.add(new Paragraph(" "));
        document.add(new Paragraph("Invoice Number : " + safeInvoiceNumber));
        document.add(new Paragraph("Shop Name : " + safeShopName));
        document.add(new Paragraph("Distributor : " + safeDistributorName));
        document.add(new Paragraph(" "));
        document.add(new Paragraph("Total Amount : \u20B9 " + safeTotalAmount));

        // QR encodes invoice number + amount so it can be scanned to
        // quickly verify the invoice, e.g. at delivery / audit time.
        try {
            String qrPayload = "INV:" + safeInvoiceNumber + "|AMT:" + safeTotalAmount;
            byte[] qrBytes = qrCodeGenerator.generateQrCode(qrPayload, 150, 150);
            Image qrImage = new Image(ImageDataFactory.create(qrBytes));
            document.add(new Paragraph(" "));
            document.add(qrImage);
        } catch (Exception e) {
            // Don't fail the whole invoice download over a QR rendering
            // hiccup — the PDF is still valid and useful without it.
        }

        document.close();

        return outputStream.toByteArray();
    }

    /**
     * Generic multi-page table PDF (Stock Summary and similar reports).
     * Column headers repeat automatically on every page via iText's
     * setSkipFirstHeader(false) — no manual page-break handling needed.
     * groupHeaders (optional) renders a second header row above the
     * column headers with colspans, e.g. "Opening | Inward | Outward |
     * Closing" each spanning its Qty/Rate/Value sub-columns.
     */
    public byte[] generateReportTablePdf(String title, String subtitle,
                                          String[] groupHeaders, int[] groupSpans,
                                          String[] columnHeaders, java.util.List<String[]> rows,
                                          String[] totalRow) {

        PdfFont bold = boldFont();
        PdfFont regular = regularFont();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(outputStream);
        PdfDocument pdfDocument = new PdfDocument(writer);
        pdfDocument.setDefaultPageSize(com.itextpdf.kernel.geom.PageSize.A4.rotate());
        Document document = new Document(pdfDocument, com.itextpdf.kernel.geom.PageSize.A4.rotate());
        document.setMargins(20, 20, 20, 20);

        document.add(new Paragraph(title).setFont(bold).setFontSize(14));
        if (subtitle != null && !subtitle.isBlank()) {
            document.add(new Paragraph(subtitle).setFont(regular).setFontSize(9).setFontColor(com.itextpdf.kernel.colors.ColorConstants.GRAY));
        }

        float[] colWidths = new float[columnHeaders.length];
        java.util.Arrays.fill(colWidths, 1f);
        com.itextpdf.layout.element.Table table = new com.itextpdf.layout.element.Table(colWidths)
                .useAllAvailableWidth();
        table.setSkipFirstHeader(false); // repeats the header block on every page

        if (groupHeaders != null && groupHeaders.length > 0) {
            for (int i = 0; i < groupHeaders.length; i++) {
                com.itextpdf.layout.element.Cell c = new com.itextpdf.layout.element.Cell(1, groupSpans[i])
                        .add(new Paragraph(groupHeaders[i]).setFont(bold).setFontSize(8))
                        .setTextAlignment(com.itextpdf.layout.properties.TextAlignment.CENTER)
                        .setBackgroundColor(com.itextpdf.kernel.colors.ColorConstants.LIGHT_GRAY);
                table.addHeaderCell(c);
            }
        }
        for (String h : columnHeaders) {
            table.addHeaderCell(new com.itextpdf.layout.element.Cell()
                    .add(new Paragraph(h).setFont(bold).setFontSize(8))
                    .setBackgroundColor(com.itextpdf.kernel.colors.ColorConstants.LIGHT_GRAY));
        }

        for (String[] row : rows) {
            for (String cell : row) {
                table.addCell(new com.itextpdf.layout.element.Cell().add(new Paragraph(cell == null ? "" : cell).setFont(regular).setFontSize(7.5f)));
            }
        }

        if (totalRow != null) {
            for (String cell : totalRow) {
                table.addCell(new com.itextpdf.layout.element.Cell()
                        .add(new Paragraph(cell == null ? "" : cell).setFont(bold).setFontSize(8))
                        .setBackgroundColor(com.itextpdf.kernel.colors.ColorConstants.LIGHT_GRAY));
            }
        }

        document.add(table);
        document.close();
        return outputStream.toByteArray();
    }

    /** One printed row of the tax invoice's item table. */
    public record InvoiceLineItem(
            int slNo,
            String description,
            String hsnSac,
            java.math.BigDecimal mrp,
            Integer quantity,
            String unit,
            java.math.BigDecimal rate,
            java.math.BigDecimal discountPercent,
            java.math.BigDecimal amount
    ) {
    }

    /** Seller or buyer block printed at the top of the tax invoice. */
    public record InvoiceParty(
            String name,
            String address,
            String cityStatePin,
            String gstin,
            String phone
    ) {
    }

    /**
     * Full itemised tax-invoice layout — Sl No / Description / HSN-SAC /
     * MRP / Qty / Rate / Disc.% / Amount per line, then CGST + SGST +
     * Round Off + Total, mirroring the printed invoices this app already
     * issues on paper. Replaces the old bare-header PDF for real invoice
     * downloads/prints (generateInvoicePdf() above is kept only for any
     * caller still relying on the old 4-field signature).
     */
    public byte[] generateTaxInvoicePdf(String invoiceNumber,
                                         java.time.LocalDate invoiceDate,
                                         String fssaiNumber,
                                         InvoiceParty seller,
                                         InvoiceParty buyer,
                                         java.util.List<InvoiceLineItem> items,
                                         java.math.BigDecimal subTotal,
                                         java.math.BigDecimal cgstAmount,
                                         java.math.BigDecimal sgstAmount,
                                         java.math.BigDecimal roundOff,
                                         java.math.BigDecimal totalAmount,
                                         String amountInWords) {

        PdfFont bold = boldFont();
        PdfFont regular = regularFont();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(outputStream);
        PdfDocument pdfDocument = new PdfDocument(writer);
        Document document = new Document(pdfDocument, com.itextpdf.kernel.geom.PageSize.A4);
        document.setMargins(24, 24, 24, 24);

        com.itextpdf.kernel.colors.Color gray = com.itextpdf.kernel.colors.ColorConstants.LIGHT_GRAY;

        document.add(new Paragraph("Tax Invoice").setFont(bold).setFontSize(14)
                .setTextAlignment(com.itextpdf.layout.properties.TextAlignment.CENTER));

        document.add(new Paragraph(nz(seller.name(), "\u2014")).setFont(bold).setFontSize(13)
                .setTextAlignment(com.itextpdf.layout.properties.TextAlignment.CENTER).setMarginTop(4));
        document.add(new Paragraph(addressLine(seller)).setFont(regular).setFontSize(9)
                .setTextAlignment(com.itextpdf.layout.properties.TextAlignment.CENTER));
        StringBuilder sellerLine2 = new StringBuilder();
        if (fssaiNumber != null && !fssaiNumber.isBlank()) {
            sellerLine2.append("FSSAI Lic. No.: ").append(fssaiNumber);
        }
        if (seller.gstin() != null && !seller.gstin().isBlank()) {
            if (sellerLine2.length() > 0) sellerLine2.append("   |   ");
            sellerLine2.append("GSTIN/UIN: ").append(seller.gstin());
        }
        if (sellerLine2.length() > 0) {
            document.add(new Paragraph(sellerLine2.toString()).setFont(regular).setFontSize(9)
                    .setTextAlignment(com.itextpdf.layout.properties.TextAlignment.CENTER));
        }
        document.add(new com.itextpdf.layout.element.LineSeparator(new com.itextpdf.kernel.pdf.canvas.draw.SolidLine(0.75f))
                .setMarginTop(6).setMarginBottom(6));

        // Buyer / invoice-meta strip
        com.itextpdf.layout.element.Table meta = new com.itextpdf.layout.element.Table(new float[]{1f, 1f}).useAllAvailableWidth();
        com.itextpdf.layout.element.Cell buyerCell = new com.itextpdf.layout.element.Cell().setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
        buyerCell.add(new Paragraph("Buyer (Bill to)").setFont(bold).setFontSize(9));
        buyerCell.add(new Paragraph(nz(buyer.name(), "\u2014")).setFont(bold).setFontSize(10));
        buyerCell.add(new Paragraph(addressLine(buyer)).setFont(regular).setFontSize(9));
        if (buyer.phone() != null && !buyer.phone().isBlank()) {
            buyerCell.add(new Paragraph("Ph: " + buyer.phone()).setFont(regular).setFontSize(9));
        }
        if (buyer.gstin() != null && !buyer.gstin().isBlank()) {
            buyerCell.add(new Paragraph("GSTIN/UIN: " + buyer.gstin()).setFont(regular).setFontSize(9));
        }
        meta.addCell(buyerCell);

        com.itextpdf.layout.element.Cell metaCell = new com.itextpdf.layout.element.Cell().setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
        metaCell.add(kv(bold, regular, "Invoice No.", nz(invoiceNumber, "\u2014")));
        metaCell.add(kv(bold, regular, "Dated", invoiceDate != null ? invoiceDate.format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy")) : "\u2014"));
        meta.addCell(metaCell);
        document.add(meta);
        document.add(new Paragraph(" ").setFontSize(4));

        // Line-items table
        float[] widths = {0.5f, 3f, 1f, 1f, 0.8f, 1f, 0.8f, 1.2f};
        com.itextpdf.layout.element.Table table = new com.itextpdf.layout.element.Table(widths).useAllAvailableWidth();
        table.setSkipFirstHeader(false);
        String[] headers = {"Sl No", "Description of Goods", "HSN/SAC", "MRP", "Qty", "Rate", "Disc.%", "Amount"};
        for (String h : headers) {
            table.addHeaderCell(new com.itextpdf.layout.element.Cell()
                    .add(new Paragraph(h).setFont(bold).setFontSize(8))
                    .setBackgroundColor(gray)
                    .setTextAlignment(com.itextpdf.layout.properties.TextAlignment.CENTER));
        }
        for (InvoiceLineItem item : items) {
            table.addCell(bodyCell(String.valueOf(item.slNo()), regular, com.itextpdf.layout.properties.TextAlignment.CENTER));
            table.addCell(bodyCell(nz(item.description(), ""), regular, com.itextpdf.layout.properties.TextAlignment.LEFT));
            table.addCell(bodyCell(nz(item.hsnSac(), ""), regular, com.itextpdf.layout.properties.TextAlignment.CENTER));
            table.addCell(bodyCell(item.mrp() != null ? String.format("%,.2f", item.mrp()) : "\u2014", regular, com.itextpdf.layout.properties.TextAlignment.RIGHT));
            table.addCell(bodyCell((item.quantity() != null ? item.quantity() : 0) + " " + nz(item.unit(), ""), regular, com.itextpdf.layout.properties.TextAlignment.CENTER));
            table.addCell(bodyCell(item.rate() != null ? String.format("%,.2f", item.rate()) : "0.00", regular, com.itextpdf.layout.properties.TextAlignment.RIGHT));
            table.addCell(bodyCell(item.discountPercent() != null ? String.format("%.2f", item.discountPercent()) : "0.00", regular, com.itextpdf.layout.properties.TextAlignment.RIGHT));
            table.addCell(bodyCell(item.amount() != null ? String.format("%,.2f", item.amount()) : "0.00", regular, com.itextpdf.layout.properties.TextAlignment.RIGHT));
        }
        document.add(table);

        // Totals block, right-aligned
        com.itextpdf.layout.element.Table totals = new com.itextpdf.layout.element.Table(new float[]{3f, 1.2f}).useAllAvailableWidth();
        totals.setMarginTop(2);
        addTotalRow(totals, bold, regular, "Sub Total", subTotal, false);
        addTotalRow(totals, bold, regular, "CGST", cgstAmount, false);
        addTotalRow(totals, bold, regular, "SGST", sgstAmount, false);
        addTotalRow(totals, bold, regular, "Round Off", roundOff, false);
        addTotalRow(totals, bold, regular, "Total", totalAmount, true);
        document.add(totals);

        document.add(new Paragraph("Amount Chargeable (in words)").setFont(bold).setFontSize(9).setMarginTop(6));
        document.add(new Paragraph("INR " + nz(amountInWords, "\u2014")).setFont(regular).setFontSize(9));

        document.add(new Paragraph("Declaration").setFont(bold).setFontSize(8).setMarginTop(10));
        document.add(new Paragraph("We declare that this invoice shows the actual price of the goods described and that all particulars are true and correct.")
                .setFont(regular).setFontSize(8));

        document.add(new Paragraph("for " + nz(seller.name(), ""))
                .setFont(bold).setFontSize(9).setMarginTop(20)
                .setTextAlignment(com.itextpdf.layout.properties.TextAlignment.RIGHT));
        document.add(new Paragraph("Authorised Signatory")
                .setFont(regular).setFontSize(8)
                .setTextAlignment(com.itextpdf.layout.properties.TextAlignment.RIGHT));

        // QR encodes invoice number + amount, same as the simple layout.
        try {
            String safeAmount = totalAmount != null ? String.format("%,.2f", totalAmount) : "0.00";
            String qrPayload = "INV:" + nz(invoiceNumber, "") + "|AMT:" + safeAmount;
            byte[] qrBytes = qrCodeGenerator.generateQrCode(qrPayload, 100, 100);
            Image qrImage = new Image(ImageDataFactory.create(qrBytes));
            document.add(qrImage.setMarginTop(4));
        } catch (Exception e) {
            // Same as generateInvoicePdf(): never fail the whole download over QR rendering.
        }

        document.close();
        return outputStream.toByteArray();
    }

    private com.itextpdf.layout.element.Cell bodyCell(String text, PdfFont font, com.itextpdf.layout.properties.TextAlignment align) {
        return new com.itextpdf.layout.element.Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(8))
                .setTextAlignment(align)
                .setPadding(3);
    }

    /** Adds one label/value row (two cells) to a 2-column totals table. */
    private void addTotalRow(com.itextpdf.layout.element.Table totals, PdfFont bold, PdfFont regular,
                              String label, java.math.BigDecimal value, boolean emphasize) {
        PdfFont font = emphasize ? bold : regular;
        float size = emphasize ? 10.5f : 9f;
        String formatted = "\u20B9 " + String.format("%,.2f", value != null ? value : java.math.BigDecimal.ZERO);

        totals.addCell(new com.itextpdf.layout.element.Cell()
                .add(new Paragraph(label).setFont(font).setFontSize(size))
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
                .setTextAlignment(com.itextpdf.layout.properties.TextAlignment.RIGHT));
        totals.addCell(new com.itextpdf.layout.element.Cell()
                .add(new Paragraph(formatted).setFont(font).setFontSize(size))
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
                .setTextAlignment(com.itextpdf.layout.properties.TextAlignment.RIGHT));
    }

    private com.itextpdf.layout.element.Cell kv(PdfFont bold, PdfFont regular, String label, String value) {
        Paragraph p = new Paragraph().setFontSize(9);
        p.add(new com.itextpdf.layout.element.Text(label + " : ").setFont(bold));
        p.add(new com.itextpdf.layout.element.Text(value).setFont(regular));
        return new com.itextpdf.layout.element.Cell().add(p).setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
    }

    private String addressLine(InvoiceParty p) {
        StringBuilder sb = new StringBuilder();
        if (p.address() != null && !p.address().isBlank()) sb.append(p.address());
        if (p.cityStatePin() != null && !p.cityStatePin().isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.cityStatePin());
        }
        return sb.length() > 0 ? sb.toString() : "\u2014";
    }

    private String nz(String s, String fallback) {
        return s != null && !s.isBlank() ? s : fallback;
    }
}
