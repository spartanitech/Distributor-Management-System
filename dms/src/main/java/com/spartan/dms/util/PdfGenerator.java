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
}
