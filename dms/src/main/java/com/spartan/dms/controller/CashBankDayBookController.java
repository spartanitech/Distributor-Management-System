package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.BookResponse;
import com.spartan.dms.service.CashBankDayBookService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
public class CashBankDayBookController {

    private final CashBankDayBookService bookService;

    @GetMapping("/cash-book")
    public ResponseEntity<ApiResponse<BookResponse>> getCashBook(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.success("Cash book fetched", bookService.getCashBook(fromDate, toDate, search, page, size)));
    }

    @GetMapping("/bank-book")
    public ResponseEntity<ApiResponse<BookResponse>> getBankBook(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.success("Bank book fetched", bookService.getBankBook(fromDate, toDate, search, page, size)));
    }

    @GetMapping("/day-book")
    public ResponseEntity<ApiResponse<BookResponse>> getDayBook(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.success("Day book fetched", bookService.getDayBook(fromDate, toDate, search, page, size)));
    }

    /* ---------- Export: /export/{csv|excel|pdf}?bookType=CASH|BANK|DAY ---------- */

    @GetMapping(value = "/books/export/csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam String bookType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String search) {

        byte[] csv = bookService.exportCsv(bookType, fromDate, toDate, search);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + bookType.toLowerCase() + "-book.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @GetMapping(value = "/books/export/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam String bookType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String search) throws IOException {

        byte[] excel = bookService.exportExcel(bookType, fromDate, toDate, search);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + bookType.toLowerCase() + "-book.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel);
    }

    @GetMapping(value = "/books/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(
            @RequestParam String bookType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String search) {

        byte[] pdf = bookService.exportPdf(bookType, fromDate, toDate, search);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + bookType.toLowerCase() + "-book.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
