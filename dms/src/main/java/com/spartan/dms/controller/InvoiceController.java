package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.InvoiceRequest;
import com.spartan.dms.dto.InvoiceResponse;
import com.spartan.dms.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/invoices")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class InvoiceController {

    private final InvoiceService invoiceService;

    // Ownership/level checks now live in InvoiceService (which of the 3
    // invoice-hierarchy legs is being created, and who's allowed to).
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @PostMapping
    public ResponseEntity<ApiResponse<InvoiceResponse>> createInvoice(
            @RequestBody InvoiceRequest request) {

        return ResponseEntity.ok(invoiceService.createInvoice(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<InvoiceResponse>> updateInvoice(
            @PathVariable Long id,
            @RequestBody InvoiceRequest request) {

        return ResponseEntity.ok(invoiceService.updateInvoice(id, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteInvoice(
            @PathVariable Long id) {

        return ResponseEntity.ok(invoiceService.deleteInvoice(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<InvoiceResponse>> getInvoiceById(
            @PathVariable Long id) {

        return ResponseEntity.ok(invoiceService.getInvoiceById(id));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<InvoiceResponse>>> getAllInvoices() {

        return ResponseEntity.ok(invoiceService.getAllInvoices());
    }

    // Bills raised against the caller by the tier above them (Admin ->
    // Super Stockist, Super Stockist -> Distributor). Deliberately a
    // SEPARATE endpoint from GET /invoices: that list is "invoices I
    // raised", so a party's own purchase bills would otherwise be
    // invisible and unpayable. Admin gets an empty list -- Company is the
    // top of the chain and is never billed.
    @PreAuthorize("hasRole('SUPER_STOCKIST') or hasRole('DISTRIBUTOR')")
    @GetMapping("/billed-to-me")
    public ResponseEntity<ApiResponse<List<InvoiceResponse>>> getInvoicesBilledToMe() {

        return ResponseEntity.ok(invoiceService.getInvoicesBilledToMe());
    }



    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<InvoiceResponse>>> searchInvoice(
            @RequestParam String keyword) {

        return ResponseEntity.ok(invoiceService.searchInvoice(keyword));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<String>> updateInvoiceStatus(
            @PathVariable Long id,
            @RequestParam String status) {

        return ResponseEntity.ok(invoiceService.updateInvoiceStatus(id, status));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<ApiResponse<String>> downloadInvoice(
            @PathVariable Long id) {

        return ResponseEntity.ok(invoiceService.downloadInvoice(id));
    }

    // Actual PDF bytes (the endpoint above only returns a status message).
    // Frontend can point a link/window.open at this to get a real download.
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadInvoicePdf(
            @PathVariable Long id) {

        byte[] pdf = invoiceService.generateInvoicePdfBytes(id);

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=invoice-" + id + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/{id}/print")
    public ResponseEntity<ApiResponse<String>> printInvoice(
            @PathVariable Long id) {

        return ResponseEntity.ok(invoiceService.printInvoice(id));
    }
}