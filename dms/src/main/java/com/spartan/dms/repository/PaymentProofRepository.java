package com.spartan.dms.repository;

import com.spartan.dms.entity.PaymentProof;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentProofRepository extends JpaRepository<PaymentProof, Long> {

    List<PaymentProof> findByPaymentId(Long paymentId);

    List<PaymentProof> findByFileType(String fileType);

    List<PaymentProof> findByPaymentIdAndFileType(Long paymentId, String fileType);
}