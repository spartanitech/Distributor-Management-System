package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.PaymentProofRequest;
import com.spartan.dms.dto.PaymentProofResponse;
import com.spartan.dms.entity.Payment;
import com.spartan.dms.entity.PaymentProof;
import com.spartan.dms.exception.FileStorageException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.mapper.PaymentProofMapper;
import com.spartan.dms.repository.PaymentProofRepository;
import com.spartan.dms.repository.PaymentRepository;
import com.spartan.dms.security.SecurityUtils;
import com.spartan.dms.util.FileUploadUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentProofService {

    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "application/pdf"
    );

    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024; // 5 MB

    private final PaymentProofRepository paymentProofRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentProofMapper paymentProofMapper;
    private final FileUploadUtil fileUploadUtil;
    private final SecurityUtils securityUtils;

    @Value("${app.upload.payment-proofs-dir:paymentproofs}")
    private String uploadSubdirectory;

    // A payment's own party (distributor or super-stockist, whichever it
    // actually has) determines who may view/upload/delete its proof files.
    // payment.getDistributor() is null for a COMPANY_TO_SUPER_STOCKIST
    // payment (see Payment entity) -- calling assertDistributorAccess()
    // straight off it, as every method here used to, threw a
    // NullPointerException instead of a proper 403/404 the moment a
    // Super Stockist tried to manage their own proof for that kind of
    // payment.
    private void assertProofAccess(Payment payment) {
        if (payment.getDistributor() != null) {
            securityUtils.assertDistributorAccess(payment.getDistributor().getId());
        } else if (payment.getSuperStockist() != null) {
            securityUtils.assertSuperStockistAccess(payment.getSuperStockist().getId());
        } else if (!securityUtils.isAdmin()) {
            throw new com.spartan.dms.exception.ForbiddenException("Not authorized to access this payment's proof");
        }
    }

    public ApiResponse<PaymentProofResponse> createPaymentProof(PaymentProofRequest request) {

        Payment payment = paymentRepository.findById(request.getPaymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        PaymentProof paymentProof = paymentProofMapper.toEntity(request);
        paymentProof.setPayment(payment);

        paymentProof = paymentProofRepository.save(paymentProof);

        return ApiResponse.<PaymentProofResponse>builder()
                .success(true)
                .message("Payment Proof Created Successfully")
                .data(paymentProofMapper.toResponse(paymentProof))
                .build();
    }

    public ApiResponse<List<PaymentProofResponse>> getAllPaymentProofs() {

        List<PaymentProofResponse> proofs = paymentProofRepository.findAll()
                .stream()
                .map(paymentProofMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<PaymentProofResponse>>builder()
                .success(true)
                .message("Payment Proof List")
                .data(proofs)
                .build();
    }

    public ApiResponse<PaymentProofResponse> getPaymentProofById(Long id) {

        PaymentProof paymentProof = paymentProofRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment Proof not found"));

        return ApiResponse.<PaymentProofResponse>builder()
                .success(true)
                .message("Payment Proof Details")
                .data(paymentProofMapper.toResponse(paymentProof))
                .build();
    }

    public ApiResponse<PaymentProofResponse> updatePaymentProof(Long id,
                                                                PaymentProofRequest request) {

        PaymentProof paymentProof = paymentProofRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment Proof not found"));

        Payment payment = paymentRepository.findById(request.getPaymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        paymentProofMapper.updateEntity(request, paymentProof);
        paymentProof.setPayment(payment);

        paymentProof = paymentProofRepository.save(paymentProof);

        return ApiResponse.<PaymentProofResponse>builder()
                .success(true)
                .message("Payment Proof Updated Successfully")
                .data(paymentProofMapper.toResponse(paymentProof))
                .build();
    }

    public ApiResponse<String> deletePaymentProof(Long id) {

        PaymentProof paymentProof = paymentProofRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment Proof not found"));

        deleteFileQuietly(paymentProof.getFilePath());
        paymentProofRepository.delete(paymentProof);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Payment Proof Deleted Successfully")
                .data("Deleted")
                .build();
    }

    /**
     * The single, authoritative proof-upload implementation.
     *
     * Root cause this fixes: the previous version never wrote the uploaded
     * bytes anywhere — it built a PaymentProof row with
     * filePath = file.getOriginalFilename() (just the raw client-supplied
     * name, not a real path) and never touched Payment.proofImage. So the
     * file's content was silently discarded, and GET /api/v1/payments
     * (which is what the payments table actually renders from) had nothing
     * to show, hence the permanent "Not uploaded" label.
     *
     * This version:
     *   1. Validates the file (type + size) before touching the disk.
     *   2. Persists the bytes via FileUploadUtil to uploads/paymentproofs/.
     *   3. Creates a PaymentProof row with the real file_name, file_path,
     *      file_type, file_size (uploaded_at comes from BaseEntity's
     *      @PrePersist createdAt, exposed as `uploadedAt` in the DTO).
     *   4. Syncs Payment.proofImage with the same public URL so payment
     *      history / dashboard / any screen reading PaymentResponse
     *      directly reflects the upload immediately, with no extra join.
     */
    @Transactional
    public ApiResponse<PaymentProofResponse> uploadProof(Long paymentId, MultipartFile file) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        assertProofAccess(payment);

        if (file == null || file.isEmpty()) {
            throw new FileStorageException("Please select a file to upload.");
        }

        FileUploadUtil.StoredFile stored = fileUploadUtil.uploadFile(
                file, uploadSubdirectory, ALLOWED_CONTENT_TYPES, MAX_FILE_SIZE_BYTES);

        PaymentProof proof = PaymentProof.builder()
                .payment(payment)
                .fileName(file.getOriginalFilename())
                .filePath(stored.relativePath())
                .fileType(file.getContentType())
                .fileSize(file.getSize())
                .build();

        proof = paymentProofRepository.save(proof);

        // Keep Payment.proofImage in sync so PaymentResponse.proofFilePath /
        // .proofImage are correct without requiring a separate lookup. This
        // points at the access-controlled download endpoint (keyed by this
        // proof's own id), never the raw /uploads/... path -- see
        // PaymentProofMapper.toPublicUrl() for why.
        payment.setProofImage("/api/v1/payment-proofs/file/" + proof.getId());
        payment.setProofFileName(proof.getFileName());
        paymentRepository.save(payment);

        log.info("Uploaded payment proof id={} paymentId={} file={} size={}B",
                proof.getId(), paymentId, stored.relativePath(), file.getSize());

        return ApiResponse.<PaymentProofResponse>builder()
                .success(true)
                .message("Payment Proof Uploaded Successfully")
                .data(paymentProofMapper.toResponse(proof))
                .build();
    }

    public ApiResponse<List<PaymentProofResponse>> getPaymentProofs(Long paymentId) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        assertProofAccess(payment);

        List<PaymentProofResponse> proofs = paymentProofRepository
                .findByPaymentId(paymentId)
                .stream()
                .map(paymentProofMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<PaymentProofResponse>>builder()
                .success(true)
                .message("Payment Proof List")
                .data(proofs)
                .build();
    }

    @Transactional
    public ApiResponse<String> deleteProof(Long proofId) {

        PaymentProof proof = paymentProofRepository.findById(proofId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment Proof not found"));

        Payment payment = proof.getPayment();

        if (payment != null) {
            assertProofAccess(payment);
        }

        deleteFileQuietly(proof.getFilePath());
        paymentProofRepository.delete(proof);

        // If this was the proof currently reflected on the Payment row,
        // clear it (or fall back to another remaining proof, if any) so the
        // UI doesn't keep pointing at a deleted file.
        if (payment != null) {
            List<PaymentProof> remaining = paymentProofRepository.findByPaymentId(payment.getId());
            PaymentProof latest = remaining.isEmpty() ? null : remaining.get(remaining.size() - 1);
            payment.setProofImage(latest == null ? null : "/api/v1/payment-proofs/file/" + latest.getId());
            payment.setProofFileName(latest == null ? null : latest.getFileName());
            paymentRepository.save(payment);
        }

        return ApiResponse.<String>builder()
                .success(true)
                .message("Payment Proof Deleted Successfully")
                .data("Deleted")
                .build();
    }

    /**
     * Loads a proof's file bytes for the access-controlled download
     * endpoint (PaymentProofController GET /file/{proofId}) -- the only
     * legitimate way to fetch a proof's actual content now that
     * uploads/paymentproofs/** is excluded from static serving (see
     * WebConfig). Runs the exact same ownership check as
     * upload/list/delete before touching the file.
     */
    public record ProofFile(byte[] bytes, String fileName, String contentType) {}

    public ProofFile downloadProof(Long proofId) {

        PaymentProof proof = paymentProofRepository.findById(proofId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment Proof not found"));

        if (proof.getPayment() != null) {
            assertProofAccess(proof.getPayment());
        } else if (!securityUtils.isAdmin()) {
            throw new com.spartan.dms.exception.ForbiddenException("Not authorized to access this payment's proof");
        }

        java.nio.file.Path path = fileUploadUtil.getFile(proof.getFilePath());
        byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(path);
        } catch (java.io.IOException e) {
            throw new ResourceNotFoundException("Proof file is missing from storage");
        }

        String contentType = proof.getFileType() != null ? proof.getFileType() : "application/octet-stream";
        return new ProofFile(bytes, proof.getFileName(), contentType);
    }

    private void deleteFileQuietly(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            fileUploadUtil.deleteFile(relativePath);
        } catch (Exception e) {
            log.warn("Could not delete proof file '{}' from disk: {}", relativePath, e.getMessage());
        }
    }
}
