package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.PaymentRequest;
import com.spartan.dms.dto.PaymentResponse;
import com.spartan.dms.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PaymentController {

    private final PaymentService paymentService;

    // Fine-grained ownership check (own distributor/super-stockist only,
    // and only for the parties valid on that invoice's level) now lives in
    // PaymentService.createPayment via resolvePaymentParties(). SUPER_STOCKIST
    // is included here because they're the payer on COMPANY_TO_SUPER_STOCKIST
    // invoices.
    @PreAuthorize("hasRole('ADMIN') or hasRole('DISTRIBUTOR') or hasRole('SUPER_STOCKIST')")
    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> createPayment(
            @RequestBody PaymentRequest request) {

        return ResponseEntity.ok(paymentService.createPayment(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PaymentResponse>> updatePayment(
            @PathVariable Long id,
            @RequestBody PaymentRequest request) {

        return ResponseEntity.ok(paymentService.updatePayment(id, request));
    }

    // Fine-grained ownership check (only their own payment) now lives in
    // PaymentService.deletePayment via assertPaymentAccess.
    @PreAuthorize("hasRole('ADMIN') or hasRole('DISTRIBUTOR') or hasRole('SUPER_STOCKIST')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deletePayment(
            @PathVariable Long id) {

        return ResponseEntity.ok(paymentService.deletePayment(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPaymentById(
            @PathVariable Long id) {

        return ResponseEntity.ok(paymentService.getPaymentById(id));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getAllPayments() {

        return ResponseEntity.ok(paymentService.getAllPayments());
    }

    @GetMapping("/invoice/{invoiceId}")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getPaymentsByInvoice(
            @PathVariable Long invoiceId) {

        return ResponseEntity.ok(paymentService.getPaymentsByInvoice(invoiceId));
    }

    @PostMapping("/{id}/upload-proof")
    public ResponseEntity<ApiResponse<String>> uploadPaymentProof(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {

        return ResponseEntity.ok(paymentService.uploadPaymentProof(id, file));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/verify")
    public ResponseEntity<ApiResponse<String>> verifyPayment(
            @PathVariable Long id) {

        return ResponseEntity.ok(paymentService.verifyPayment(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<String>> rejectPayment(
            @PathVariable Long id) {

        return ResponseEntity.ok(paymentService.rejectPayment(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<String>> updatePaymentStatus(
            @PathVariable Long id,
            @RequestParam String status) {

        return ResponseEntity.ok(paymentService.updatePaymentStatus(id, status));
    }

    // Payment register PDF. Scoped in the service to exactly the rows this
    // caller can see on screen -- no extra @PreAuthorize needed beyond the
    // class-level one, since an admin, Super Stockist and Distributor each
    // get their own view of the same endpoint.
    @GetMapping(value = "/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPaymentsPdf() {

        byte[] pdf = paymentService.exportPaymentsPdf();

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=payments.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}