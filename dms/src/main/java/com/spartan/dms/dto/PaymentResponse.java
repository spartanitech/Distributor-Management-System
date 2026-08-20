package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private Long id;
    private Long invoiceId;
    private String invoiceNumber;
    private String invoiceLevel;
    private String shopName;
    private String distributorName;
    private String superStockistName;
    private BigDecimal amount;
    private String paymentMethod;
    private String paymentStatus;
    private String transactionId;
    private LocalDate paymentDate;

    /** Legacy single-file field, kept for backward compatibility. */
    private String proofImage;

    /**
     * Publicly reachable URL of the most recently uploaded proof
     * (e.g. "/uploads/paymentproofs/&lt;uuid&gt;_receipt.jpg"), populated from
     * the PaymentProof record created by PaymentProofService.uploadProof().
     * This is what the payments table checks to decide whether to render
     * the "Not uploaded" label or the paperclip/attachment icon.
     */
    private String proofFilePath;

    /** Original file name of the most recently uploaded proof, for display. */
    private String proofFileName;

    /** The most recently uploaded proof's own id, needed to call DELETE /payment-proofs/{id}. */
    private Long proofId;

    private Boolean verified;
}
