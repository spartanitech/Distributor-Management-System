package com.spartan.dms.repository;

import com.spartan.dms.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AuditLog> findByEntityTypeOrderByCreatedAtDesc(String entityType, Pageable pageable);

    List<AuditLog> findByPerformedByOrderByCreatedAtDesc(String performedBy, Pageable pageable);
}
