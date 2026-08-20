package com.spartan.dms.mapper;

import com.spartan.dms.dto.PaymentRequest;
import com.spartan.dms.dto.PaymentResponse;
import com.spartan.dms.entity.Payment;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    private final ModelMapper modelMapper;

    public PaymentMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public Payment toEntity(PaymentRequest request) {
        return modelMapper.map(request, Payment.class);
    }

    public PaymentResponse toResponse(Payment payment) {

        PaymentResponse response = modelMapper.map(payment, PaymentResponse.class);

        if (payment.getInvoice() != null) {

            response.setInvoiceId(payment.getInvoice().getId());

            response.setInvoiceNumber(payment.getInvoice().getInvoiceNumber());

            if (payment.getInvoice().getShop() != null) {
                response.setShopName(payment.getInvoice().getShop().getShopName());
            }

            if (payment.getInvoice().getDistributor() != null) {
                response.setDistributorName(payment.getInvoice().getDistributor().getDistributorName());
            }

            if (payment.getInvoice().getInvoiceLevel() != null) {
                response.setInvoiceLevel(payment.getInvoice().getInvoiceLevel().name());
            }
        }

        if (payment.getSuperStockist() != null) {
            response.setSuperStockistName(payment.getSuperStockist().getSuperStockistName());
        }

        // payment.proofImage is kept in sync (as the access-controlled
        // /api/v1/payment-proofs/file/{id} URL -- see WebConfig/
        // PaymentProofService for why it's not a raw /uploads/... path) by
        // PaymentProofService every time a proof is uploaded, so the same
        // value is exposed here under both the legacy `proofImage` name and
        // the `proofFilePath` name the frontend's renderProofCell() checks.
        response.setProofFilePath(payment.getProofImage());
        response.setProofFileName(payment.getProofFileName());
        response.setProofId(extractProofId(payment.getProofImage()));

        return response;
    }

    // payment.proofImage is "/api/v1/payment-proofs/file/{proofId}" -- pull
    // the id back out so the frontend can call DELETE /payment-proofs/{id}
    // without needing a separate lookup just to get that id.
    private Long extractProofId(String proofImageUrl) {
        if (proofImageUrl == null || proofImageUrl.isBlank()) {
            return null;
        }
        int lastSlash = proofImageUrl.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == proofImageUrl.length() - 1) {
            return null;
        }
        try {
            return Long.valueOf(proofImageUrl.substring(lastSlash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void updateEntity(PaymentRequest request, Payment payment) {
        modelMapper.map(request, payment);
    }
}
