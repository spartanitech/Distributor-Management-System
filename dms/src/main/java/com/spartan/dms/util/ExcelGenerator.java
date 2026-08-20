package com.spartan.dms.util;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Component
public class ExcelGenerator {

    // BUG-L10: currently unused (ProductService's export path, if any, goes
    // through generateGenericExcel() below instead) — kept as-is, available
    // for a future dedicated product-catalog export.
    public byte[] generateProductExcel(List<String[]> products) throws IOException {

        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Products");

        Row header = sheet.createRow(0);

        header.createCell(0).setCellValue("Product Code");
        header.createCell(1).setCellValue("Product Name");
        header.createCell(2).setCellValue("Category");
        header.createCell(3).setCellValue("Price");
        header.createCell(4).setCellValue("Stock");

        int rowNum = 1;

        for (String[] product : products) {

            Row row = sheet.createRow(rowNum++);

            for (int i = 0; i < product.length; i++) {
                row.createCell(i).setCellValue(product[i]);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        workbook.write(out);
        workbook.close();

        return out.toByteArray();
    }

    /**
     * Generic export used by ReportService for the sales/invoice/payment/
     * location-breakdown reports — unlike generateProductExcel() above,
     * this isn't hardcoded to a single column layout.
     */
    public byte[] generateGenericExcel(String sheetName, String[] headers, List<String[]> rows) throws IOException {

        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(sheetName);

        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }

        int rowNum = 1;
        for (String[] rowData : rows) {
            Row row = sheet.createRow(rowNum++);
            for (int i = 0; i < rowData.length; i++) {
                row.createCell(i).setCellValue(rowData[i]);
            }
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        return out.toByteArray();
    }
}