package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.AssignDistributorsRequest;
import com.spartan.dms.dto.DistributorResponse;
import com.spartan.dms.dto.SuperStockistRequest;
import com.spartan.dms.dto.SuperStockistResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.SuperStockist;
import com.spartan.dms.exception.DuplicateResourceException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.mapper.DistributorMapper;
import com.spartan.dms.mapper.SuperStockistMapper;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.SuperStockistRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SuperStockistService {

    private final SuperStockistRepository superStockistRepository;
    private final DistributorRepository distributorRepository;
    private final com.spartan.dms.repository.InvoiceRepository invoiceRepository;
    private final com.spartan.dms.repository.UserRepository userRepository;
    private final com.spartan.dms.repository.WarehouseRepository warehouseRepository;
    private final com.spartan.dms.repository.StockTransferRepository stockTransferRepository;
    private final com.spartan.dms.repository.ProductSuperStockistPriceRepository productSuperStockistPriceRepository;
    private final com.spartan.dms.repository.PurchaseReturnRepository purchaseReturnRepository;
    private final SuperStockistMapper superStockistMapper;
    private final DistributorMapper distributorMapper;
    private final SecurityUtils securityUtils;
    private final AuditLogService auditLogService;

    public ApiResponse<SuperStockistResponse> createSuperStockist(SuperStockistRequest request) {

        if (superStockistRepository.existsByMobileNumber(request.getMobileNumber())) {
            throw new DuplicateResourceException("Mobile Number already exists");
        }

        if (request.getEmail() != null && !request.getEmail().isBlank()
                && superStockistRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already exists");
        }

        if (request.getGstNumber() != null && !request.getGstNumber().isBlank()
                && superStockistRepository.existsByGstNumber(request.getGstNumber())) {
            throw new DuplicateResourceException("GST Number already exists");
        }

        SuperStockist superStockist = superStockistMapper.toEntity(request);
        superStockist = superStockistRepository.save(superStockist);

        auditLogService.log("CREATE", "SUPER_STOCKIST", superStockist.getId(),
                "Created super stockist " + superStockist.getSuperStockistName());

        return ApiResponse.<SuperStockistResponse>builder()
                .success(true)
                .message("Super Stockist Created Successfully")
                .data(superStockistMapper.toResponse(superStockist, 0))
                .build();
    }

    public ApiResponse<SuperStockistResponse> updateSuperStockist(Long id, SuperStockistRequest request) {

        SuperStockist superStockist = superStockistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));

        if (request.getMobileNumber() != null
                && superStockistRepository.existsByMobileNumberAndIdNot(request.getMobileNumber(), id)) {
            throw new DuplicateResourceException("Mobile Number already exists");
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()
                && superStockistRepository.existsByEmailAndIdNot(request.getEmail(), id)) {
            throw new DuplicateResourceException("Email already exists");
        }
        if (request.getGstNumber() != null && !request.getGstNumber().isBlank()
                && superStockistRepository.existsByGstNumberAndIdNot(request.getGstNumber(), id)) {
            throw new DuplicateResourceException("GST Number already exists");
        }

        superStockistMapper.updateEntity(request, superStockist);

        superStockist = superStockistRepository.save(superStockist);

        auditLogService.log("UPDATE", "SUPER_STOCKIST", superStockist.getId(),
                "Updated super stockist " + superStockist.getSuperStockistName());

        long count = distributorRepository.countBySuperStockistId(id);

        return ApiResponse.<SuperStockistResponse>builder()
                .success(true)
                .message("Super Stockist Updated Successfully")
                .data(superStockistMapper.toResponse(superStockist, count))
                .build();
    }

    public ApiResponse<String> deleteSuperStockist(Long id) {

        SuperStockist superStockist = superStockistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));

        long assignedCount = distributorRepository.countBySuperStockistId(id);
        if (assignedCount > 0) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "Cannot delete: " + assignedCount + " distributor(s) are still assigned to this Super Stockist. "
                            + "Reassign or unassign them first.");
        }

        List<String> blockers = new java.util.ArrayList<>();
        if (invoiceRepository.existsBySuperStockistId(id)) blockers.add("invoices");
        if (userRepository.existsBySuperStockistId(id)) blockers.add("a linked login account");
        if (warehouseRepository.findBySuperStockistId(id).size() > 0) blockers.add("warehouse/stock records");
        if (stockTransferRepository.existsByFromSuperStockistId(id) || stockTransferRepository.existsByToSuperStockistId(id)) {
            blockers.add("stock transfers");
        }
        if (productSuperStockistPriceRepository.existsBySuperStockistId(id)) blockers.add("custom product pricing");
        if (purchaseReturnRepository.existsBySuperStockistId(id)) blockers.add("returns");
        if (!blockers.isEmpty()) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "Cannot delete: this Super Stockist still has " + String.join(", ", blockers)
                            + ". Set it to Inactive instead of deleting it.");
        }

        superStockistRepository.delete(superStockist);

        auditLogService.log("DELETE", "SUPER_STOCKIST", id, "Deleted super stockist");

        return ApiResponse.<String>builder()
                .success(true)
                .message("Super Stockist Deleted Successfully")
                .data("Deleted")
                .build();
    }

    public ApiResponse<SuperStockistResponse> getSuperStockistById(Long id) {

        securityUtils.assertSuperStockistAccess(id);

        SuperStockist superStockist = superStockistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));

        long count = distributorRepository.countBySuperStockistId(id);

        return ApiResponse.<SuperStockistResponse>builder()
                .success(true)
                .message("Super Stockist Details")
                .data(superStockistMapper.toResponse(superStockist, count))
                .build();
    }

    // Admin-only: every Super Stockist with its assigned-distributor count.
    public ApiResponse<List<SuperStockistResponse>> getAllSuperStockists() {

        // One grouped COUNT for every Super Stockist instead of a COUNT
        // per row: this used to fire 1 + N queries just to render the list.
        java.util.Map<Long, Long> distributorCounts = new java.util.HashMap<>();
        for (Object[] row : distributorRepository.countGroupedBySuperStockist()) {
            distributorCounts.put((Long) row[0], (Long) row[1]);
        }

        List<SuperStockistResponse> superStockists = superStockistRepository.findAll()
                .stream()
                .map(ss -> superStockistMapper.toResponse(ss, distributorCounts.getOrDefault(ss.getId(), 0L)))
                .collect(Collectors.toList());

        return ApiResponse.<List<SuperStockistResponse>>builder()
                .success(true)
                .message("Super Stockist List")
                .data(superStockists)
                .build();
    }

    public ApiResponse<List<SuperStockistResponse>> searchSuperStockist(String keyword) {

        String needle = keyword.toLowerCase();

        List<SuperStockistResponse> superStockists = superStockistRepository.findAll()
                .stream()
                .filter(ss ->
                        (ss.getSuperStockistName() != null && ss.getSuperStockistName().toLowerCase().contains(needle))
                                || (ss.getMobileNumber() != null && ss.getMobileNumber().contains(keyword))
                                || (ss.getEmail() != null && ss.getEmail().toLowerCase().contains(needle))
                                || (ss.getDistrict() != null && ss.getDistrict().toLowerCase().contains(needle)))
                .map(ss -> superStockistMapper.toResponse(ss, distributorRepository.countBySuperStockistId(ss.getId())))
                .collect(Collectors.toList());

        return ApiResponse.<List<SuperStockistResponse>>builder()
                .success(true)
                .message("Super Stockist Search Result")
                .data(superStockists)
                .build();
    }

    public ApiResponse<String> updateSuperStockistStatus(Long id, Boolean status) {

        SuperStockist superStockist = superStockistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));

        superStockist.setActive(status);
        superStockistRepository.save(superStockist);

        auditLogService.log("STATUS_CHANGE", "SUPER_STOCKIST", id,
                "Set active=" + status + " for super stockist " + superStockist.getSuperStockistName());

        return ApiResponse.<String>builder()
                .success(true)
                .message("Super Stockist Status Updated Successfully")
                .data("Success")
                .build();
    }

    // Admin-only. Sets the desired complete set of distributors assigned to
    // this Super Stockist: unassigns anyone currently linked who isn't in
    // the new list, then assigns everyone in the list. Idempotent by design
    // (see AssignDistributorsRequest javadoc).
    @Transactional
    public ApiResponse<List<DistributorResponse>> assignDistributors(Long id, AssignDistributorsRequest request) {

        SuperStockist superStockist = superStockistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));

        List<Long> desiredIds = request.getDistributorIds() == null
                ? List.of()
                : request.getDistributorIds();

        List<Distributor> currentlyAssigned = distributorRepository.findBySuperStockistId(id);
        for (Distributor distributor : currentlyAssigned) {
            if (!desiredIds.contains(distributor.getId())) {
                distributor.setSuperStockist(null);
                distributorRepository.save(distributor);
            }
        }

        for (Long distributorId : desiredIds) {
            Distributor distributor = distributorRepository.findById(distributorId)
                    .orElseThrow(() -> new ResourceNotFoundException("Distributor not found: " + distributorId));
            distributor.setSuperStockist(superStockist);
            distributorRepository.save(distributor);
        }

        auditLogService.log("ASSIGN", "SUPER_STOCKIST", id,
                "Assigned " + desiredIds.size() + " distributor(s) to " + superStockist.getSuperStockistName());

        List<DistributorResponse> assigned = distributorRepository.findBySuperStockistId(id)
                .stream()
                .map(distributorMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<DistributorResponse>>builder()
                .success(true)
                .message("Distributors Assigned Successfully")
                .data(assigned)
                .build();
    }

    // Distributors assigned to a given Super Stockist. ADMIN can view any
    // Super Stockist's list; a SUPER_STOCKIST login can only view its own
    // (enforced by assertSuperStockistAccess) — this is what backs both the
    // admin "assigned distributors" screen and the Super Stockist portal's
    // own "Assigned Distributors" sidebar page.
    public ApiResponse<List<DistributorResponse>> getAssignedDistributors(Long id) {

        securityUtils.assertSuperStockistAccess(id);

        superStockistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));

        List<DistributorResponse> distributors = distributorRepository.findBySuperStockistId(id)
                .stream()
                .map(distributorMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<DistributorResponse>>builder()
                .success(true)
                .message("Assigned Distributors")
                .data(distributors)
                .build();
    }

    // Convenience for the logged-in Super Stockist's own dashboard/sidebar
    // page — resolves "me" via SecurityUtils instead of requiring the
    // frontend to know its own super_stockist_id.
    public ApiResponse<List<DistributorResponse>> getMyAssignedDistributors() {
        Long scopedId = securityUtils.getScopedSuperStockistId();
        return getAssignedDistributors(scopedId);
    }

    public ApiResponse<SuperStockistResponse> getMyProfile() {
        Long scopedId = securityUtils.getScopedSuperStockistId();
        return getSuperStockistById(scopedId);
    }
}
