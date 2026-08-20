package com.spartan.dms.controller;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.PaymentProofResponse;
import com.spartan.dms.service.PaymentProofService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payment-proofs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN') or hasRole('DISTRIBUTOR') or hasRole('SUPER_STOCKIST')")
public class PaymentProofController {

    private final PaymentProofService paymentProofService;

    /**
     * This is the endpoint the frontend actually calls right after creating
     * a payment (POST /payment-proofs/{paymentId}/upload). It now returns
     * the created PaymentProofResponse (including the browser-fetchable
     * fileUrl) instead of a bare "Success" string, so callers can render
     * the uploaded file immediately without a second round trip.
     */
    @PostMapping("/{paymentId}/upload")
    public ResponseEntity<ApiResponse<PaymentProofResponse>> uploadProof(
            @PathVariable Long paymentId,
            @RequestParam("file") MultipartFile file) {

        ApiResponse<PaymentProofResponse> response = paymentProofService.uploadProof(paymentId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<List<PaymentProofResponse>>> getPaymentProofs(
            @PathVariable Long paymentId) {

        return ResponseEntity.ok(
                paymentProofService.getPaymentProofs(paymentId)
        );
    }

    /**
     * The only browser-fetchable route for a proof's actual file content.
     * uploads/paymentproofs/** is deliberately excluded from static
     * serving (see WebConfig) since that had no way to check who was
     * asking — this endpoint re-checks the requester against the proof's
     * own payment (PaymentProofService.downloadProof -> assertProofAccess)
     * before returning any bytes.
     */
    @GetMapping("/file/{proofId}")
    public ResponseEntity<byte[]> downloadProof(@PathVariable Long proofId) {

        PaymentProofService.ProofFile file = paymentProofService.downloadProof(proofId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.fileName() + "\"")
                // BUG-M4: stops a browser from content-sniffing this
                // response into something other than the declared
                // Content-Type (e.g. treating a mislabeled upload as HTML
                // and executing it) -- closes the secondary gap alongside
                // the magic-byte check at upload time in FileUploadUtil.
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes());
    }

    @DeleteMapping("/{proofId}")
    public ResponseEntity<ApiResponse<String>> deleteProof(
            @PathVariable Long proofId) {

        return ResponseEntity.ok(
                paymentProofService.deleteProof(proofId)
        );
    }
}
