package com.spartan.dms.mapper;

import com.spartan.dms.dto.PaymentProofRequest;
import com.spartan.dms.dto.PaymentProofResponse;
import com.spartan.dms.entity.PaymentProof;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class PaymentProofMapper {

    private final ModelMapper modelMapper;

    public PaymentProofMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public PaymentProof toEntity(PaymentProofRequest request) {
        return modelMapper.map(request, PaymentProof.class);
    }

    public PaymentProofResponse toResponse(PaymentProof paymentProof) {
        PaymentProofResponse response = PaymentProofResponse.builder()
                .id(paymentProof.getId())
                .paymentId(paymentProof.getPayment() != null ? paymentProof.getPayment().getId() : null)
                .fileName(paymentProof.getFileName())
                .filePath(paymentProof.getFilePath())
                .fileUrl(toPublicUrl(paymentProof))
                .fileType(paymentProof.getFileType())
                .fileSize(paymentProof.getFileSize())
                .remarks(paymentProof.getRemarks())
                .uploadedAt(paymentProof.getCreatedAt())
                .build();
        return response;
    }

    public void updateEntity(PaymentProofRequest request, PaymentProof paymentProof) {
        modelMapper.map(request, paymentProof);
    }

    /**
     * PaymentProof.filePath is stored as a disk-relative path
     * (e.g. "paymentproofs/<uuid>_receipt.jpg"), written by FileUploadUtil,
     * but uploads/paymentproofs/** is deliberately NOT served as static
     * content (see WebConfig) — anyone authenticated could otherwise fetch
     * any party's proof just by knowing/guessing the URL, since a static
     * handler can't check payment ownership. The only browser-fetchable
     * URL for a proof is the access-controlled download endpoint, keyed by
     * this PaymentProof's own id so PaymentProofService.downloadProof()
     * can re-check ownership before returning any bytes.
     */
    private String toPublicUrl(PaymentProof paymentProof) {
        if (paymentProof.getId() == null) {
            return null;
        }
        return "/api/v1/payment-proofs/file/" + paymentProof.getId();
    }
}
