package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProofResponse {

    private Long id;

    private Long paymentId;

    private String fileName;

    /** Disk-relative path, e.g. "paymentproofs/<uuid>_receipt.jpg". */
    private String filePath;

    /** Browser-fetchable URL, e.g. "/uploads/paymentproofs/<uuid>_receipt.jpg". */
    private String fileUrl;

    private String fileType;

    private Long fileSize;

    private String remarks;

    private LocalDateTime uploadedAt;
}
