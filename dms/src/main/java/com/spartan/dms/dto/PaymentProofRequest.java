package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProofRequest {

    private Long paymentId;

    private String fileName;

    private String fileType;

    private Long fileSize;

    private String remarks;
}