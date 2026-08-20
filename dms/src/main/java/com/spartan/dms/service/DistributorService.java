package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.DistributorRequest;
import com.spartan.dms.dto.DistributorResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.exception.DuplicateResourceException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.mapper.DistributorMapper;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DistributorService {

    private final DistributorRepository distributorRepository;
    private final DistributorMapper distributorMapper;
    private final SecurityUtils securityUtils;
    private final com.spartan.dms.repository.ShopRepository shopRepository;
    private final com.spartan.dms.repository.InvoiceRepository invoiceRepository;
    private final com.spartan.dms.repository.UserRepository userRepository;
    private final com.spartan.dms.repository.ProductRequestRepository productRequestRepository;
    private final com.spartan.dms.repository.WarehouseRepository warehouseRepository;
    private final com.spartan.dms.repository.StockTransferRepository stockTransferRepository;
    private final com.spartan.dms.repository.DistributorAssignmentRepository distributorAssignmentRepository;
    private final com.spartan.dms.repository.ProductDistributorPriceRepository productDistributorPriceRepository;
    private final com.spartan.dms.repository.PurchaseReturnRepository purchaseReturnRepository;
    private final AuditLogService auditLogService;

    public ApiResponse<DistributorResponse> createDistributor(DistributorRequest request) {

        if (distributorRepository.existsByMobileNumber(request.getMobileNumber())) {
            throw new DuplicateResourceException("Mobile Number already exists");
        }

        if (request.getEmail() != null && !request.getEmail().isBlank()
                && distributorRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already exists");
        }

        if (request.getGstNumber() != null && !request.getGstNumber().isBlank()
                && distributorRepository.existsByGstNumber(request.getGstNumber())) {
            throw new DuplicateResourceException("GST Number already exists");
        }

        Distributor distributor = distributorMapper.toEntity(request);
        distributor = distributorRepository.save(distributor);

        auditLogService.log("CREATE", "DISTRIBUTOR", distributor.getId(),
                "Created distributor " + distributor.getDistributorName());

        return ApiResponse.<DistributorResponse>builder()
                .success(true)
                .message("Distributor Created Successfully")
                .data(distributorMapper.toResponse(distributor))
                .build();
    }

    public ApiResponse<List<DistributorResponse>> getAllDistributors() {

        List<DistributorResponse> distributors = distributorRepository.findAll()
                .stream()
                .map(distributorMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<DistributorResponse>>builder()
                .success(true)
                .message("Distributor List")
                .data(distributors)
                .build();
    }

    // Admin data-quality check: distributors with no Super Stockist link at
    // all. The service requires one on every create/update, so any result
    // here predates that rule (a pre-hierarchy legacy row) and needs an
    // admin to assign it -- until then it silently shows as "Unassigned" in
    // every hierarchy-scoped report/query instead of erroring.
    public ApiResponse<List<DistributorResponse>> getUnassignedDistributors() {

        securityUtils.assertAdmin();

        List<DistributorResponse> distributors = distributorRepository.findBySuperStockistIsNull()
                .stream()
                .map(distributorMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<DistributorResponse>>builder()
                .success(true)
                .message("Distributors with no Super Stockist assigned")
                .data(distributors)
                .build();
    }

    public ApiResponse<DistributorResponse> getDistributorById(Long id) {

        securityUtils.assertDistributorAccess(id);

        Distributor distributor = distributorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));

        return ApiResponse.<DistributorResponse>builder()
                .success(true)
                .message("Distributor Details")
                .data(distributorMapper.toResponse(distributor))
                .build();
    }

    public ApiResponse<DistributorResponse> updateDistributor(Long id, DistributorRequest request) {

        Distributor distributor = distributorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));

        if (request.getMobileNumber() != null
                && distributorRepository.existsByMobileNumberAndIdNot(request.getMobileNumber(), id)) {
            throw new DuplicateResourceException("Mobile Number already exists");
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()
                && distributorRepository.existsByEmailAndIdNot(request.getEmail(), id)) {
            throw new DuplicateResourceException("Email already exists");
        }
        if (request.getGstNumber() != null && !request.getGstNumber().isBlank()
                && distributorRepository.existsByGstNumberAndIdNot(request.getGstNumber(), id)) {
            throw new DuplicateResourceException("GST Number already exists");
        }

        distributorMapper.updateEntity(request, distributor);

        distributor = distributorRepository.save(distributor);

        auditLogService.log("UPDATE", "DISTRIBUTOR", distributor.getId(),
                "Updated distributor " + distributor.getDistributorName());

        return ApiResponse.<DistributorResponse>builder()
                .success(true)
                .message("Distributor Updated Successfully")
                .data(distributorMapper.toResponse(distributor))
                .build();
    }

    public ApiResponse<String> deleteDistributor(Long id) {

        Distributor distributor = distributorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));

        // A distributor with any real activity (shops, invoices, a login
        // account, stock history) can't be hard-deleted without either
        // losing that history or hitting a raw FK violation (previously
        // surfaced to the admin as a confusing generic "duplicate code/
        // barcode" error). Block with the exact reason instead, and point
        // at the Status toggle, which already supports deactivating one.
        List<String> blockers = new java.util.ArrayList<>();
        if (shopRepository.findByDistributorId(id).size() > 0) blockers.add("shops");
        if (invoiceRepository.existsByDistributorId(id)) blockers.add("invoices");
        if (userRepository.existsByDistributorId(id)) blockers.add("a linked login account");
        if (productRequestRepository.existsByDistributorId(id)) blockers.add("stock requests");
        if (warehouseRepository.findByDistributorId(id).size() > 0) blockers.add("warehouse/stock records");
        if (stockTransferRepository.existsByToDistributorId(id)) blockers.add("stock transfers");
        if (distributorAssignmentRepository.existsByDistributorId(id)) blockers.add("distributor assignments");
        if (productDistributorPriceRepository.existsByDistributorId(id)) blockers.add("custom product pricing");
        if (purchaseReturnRepository.existsByDistributorId(id)) blockers.add("purchase returns");

        if (!blockers.isEmpty()) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "Cannot delete: this distributor still has " + String.join(", ", blockers)
                            + ". Set it to Inactive instead of deleting it.");
        }

        distributorRepository.delete(distributor);

        auditLogService.log("DELETE", "DISTRIBUTOR", id, "Deleted distributor " + distributor.getDistributorName());

        return ApiResponse.<String>builder()
                .success(true)
                .message("Distributor Deleted Successfully")
                .data("Deleted")
                .build();
    }
    public ApiResponse<List<DistributorResponse>> searchDistributor(String keyword) {

        List<DistributorResponse> distributors = distributorRepository.findAll()
                .stream()
                .filter(distributor ->
                        (distributor.getDistributorName() != null &&
                                distributor.getDistributorName().toLowerCase().contains(keyword.toLowerCase()))
                                || (distributor.getMobileNumber() != null &&
                                distributor.getMobileNumber().contains(keyword))
                                || (distributor.getEmail() != null &&
                                distributor.getEmail().toLowerCase().contains(keyword.toLowerCase())))
                .map(distributorMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<DistributorResponse>>builder()
                .success(true)
                .message("Distributor Search Result")
                .data(distributors)
                .build();
    }

    public ApiResponse<String> updateDistributorStatus(Long id, Boolean status) {

        Distributor distributor = distributorRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Distributor not found"));

        distributor.setActive(status);

        distributorRepository.save(distributor);

        auditLogService.log("STATUS_CHANGE", "DISTRIBUTOR", id,
                "Distributor " + distributor.getDistributorName() + " status set to " + status);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Distributor Status Updated Successfully")
                .data("Success")
                .build();
    }
}