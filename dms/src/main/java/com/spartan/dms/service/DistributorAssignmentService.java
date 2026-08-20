package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.DistributorAssignmentActionDto;
import com.spartan.dms.dto.DistributorAssignmentRequest;
import com.spartan.dms.dto.DistributorAssignmentResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.DistributorAssignment;
import com.spartan.dms.entity.Product;
import com.spartan.dms.entity.Shop;
import com.spartan.dms.enums.AssignmentStatus;
import com.spartan.dms.enums.NotificationType;
import com.spartan.dms.exception.BadRequestException;
import com.spartan.dms.exception.ForbiddenException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.mapper.DistributorAssignmentMapper;
import com.spartan.dms.repository.DistributorAssignmentRepository;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.ProductRepository;
import com.spartan.dms.repository.ShopRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin pushes a delivery task to a Distributor — this Product, this
 * Quantity, to this Shop — and the Distributor responds Approved,
 * Modified (their own counter-quantity), or Rejected. Independent of the
 * stock-request chain: this is Admin assigning work, not a distributor
 * asking for stock.
 */
@Service
@RequiredArgsConstructor
public class DistributorAssignmentService {

    private final DistributorAssignmentRepository assignmentRepository;
    private final DistributorRepository distributorRepository;
    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final DistributorAssignmentMapper mapper;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;

    @Transactional
    public ApiResponse<DistributorAssignmentResponse> createAssignment(DistributorAssignmentRequest request) {

        if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Only an admin can create a distributor assignment");
        }
        if (request.getDistributorId() == null || request.getShopId() == null || request.getProductId() == null) {
            throw new BadRequestException("distributorId, shopId and productId are all required");
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new BadRequestException("quantity must be greater than zero");
        }

        Distributor distributor = distributorRepository.findById(request.getDistributorId())
                .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));
        Shop shop = shopRepository.findById(request.getShopId())
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        if (shop.getDistributor() == null || !shop.getDistributor().getId().equals(distributor.getId())) {
            throw new BadRequestException("That shop does not belong to this distributor");
        }

        DistributorAssignment assignment = DistributorAssignment.builder()
                .distributor(distributor)
                .shop(shop)
                .product(product)
                .quantity(request.getQuantity())
                .status(AssignmentStatus.PENDING)
                .adminRemarks(request.getRemarks())
                .assignedBy(securityUtils.getCurrentUser().getUsername())
                .build();

        assignment = assignmentRepository.save(assignment);

        notificationService.notify(com.spartan.dms.security.SecurityUtils.ROLE_DISTRIBUTOR, distributor.getId(),
                "New assignment from Admin",
                "You've been assigned to deliver " + request.getQuantity() + " x " + product.getProductName()
                        + " to " + shop.getShopName() + ".",
                NotificationType.INFO.name(), assignment.getId());

        return ApiResponse.<DistributorAssignmentResponse>builder()
                .success(true)
                .message("Assignment created")
                .data(mapper.toResponse(assignment))
                .build();
    }

    public ApiResponse<List<DistributorAssignmentResponse>> getAssignments(AssignmentStatus statusFilter) {

        List<DistributorAssignment> assignments;

        if (securityUtils.isAdmin()) {
            assignments = (statusFilter != null)
                    ? assignmentRepository.findByStatus(statusFilter)
                    : assignmentRepository.findAllWithDetails();
        } else {
            Long distributorId = securityUtils.getScopedDistributorId();
            assignments = assignmentRepository.findByDistributorId(distributorId);
            if (statusFilter != null) {
                assignments = assignments.stream().filter(a -> a.getStatus() == statusFilter).collect(Collectors.toList());
            }
        }

        return ApiResponse.<List<DistributorAssignmentResponse>>builder()
                .success(true)
                .message("Assignments")
                .data(assignments.stream().map(mapper::toResponse).collect(Collectors.toList()))
                .build();
    }

    public ApiResponse<DistributorAssignmentResponse> getAssignmentById(Long id) {

        DistributorAssignment assignment = assignmentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));

        securityUtils.assertDistributorAccess(assignment.getDistributor().getId());

        return ApiResponse.<DistributorAssignmentResponse>builder()
                .success(true)
                .message("Assignment details")
                .data(mapper.toResponse(assignment))
                .build();
    }

    /**
     * The assigned Distributor responds: APPROVED (accepts as-is), MODIFIED
     * (proposes modifiedQuantity instead), or REJECTED. One response only —
     * once actioned, the assignment is final; Admin sees the outcome via
     * getAssignments()/getAssignmentById(), which always reflects the
     * latest status.
     */
    @Transactional
    public ApiResponse<DistributorAssignmentResponse> respondToAssignment(Long id, DistributorAssignmentActionDto dto) {

        DistributorAssignment assignment = assignmentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));

        securityUtils.assertDistributorAccess(assignment.getDistributor().getId());

        if (assignment.getStatus() != AssignmentStatus.PENDING) {
            throw new BadRequestException("This assignment has already been responded to (" + assignment.getStatus() + ")");
        }
        if (dto.getStatus() == null || dto.getStatus() == AssignmentStatus.PENDING) {
            throw new BadRequestException("status must be APPROVED, MODIFIED, or REJECTED");
        }
        if (dto.getStatus() == AssignmentStatus.MODIFIED
                && (dto.getModifiedQuantity() == null || dto.getModifiedQuantity() <= 0)) {
            throw new BadRequestException("modifiedQuantity is required when responding MODIFIED");
        }

        assignment.setStatus(dto.getStatus());
        assignment.setModifiedQuantity(dto.getStatus() == AssignmentStatus.MODIFIED ? dto.getModifiedQuantity() : null);
        assignment.setDistributorRemarks(dto.getDistributorRemarks());
        assignment.setRespondedAt(LocalDateTime.now());

        assignment = assignmentRepository.save(assignment);

        String verb = switch (dto.getStatus()) {
            case APPROVED -> "approved";
            case MODIFIED -> "proposed a different quantity for";
            case REJECTED -> "rejected";
            default -> "responded to";
        };
        notificationService.notify(null, null,
                assignment.getDistributor().getDistributorName() + " " + verb + " an assignment",
                assignment.getDistributor().getDistributorName() + " " + verb + " the assignment for "
                        + assignment.getProduct().getProductName() + " to " + assignment.getShop().getShopName() + ".",
                NotificationType.INFO.name(), assignment.getId());

        return ApiResponse.<DistributorAssignmentResponse>builder()
                .success(true)
                .message("Response recorded")
                .data(mapper.toResponse(assignment))
                .build();
    }

    public ApiResponse<String> deleteAssignment(Long id) {

        if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Only an admin can delete an assignment");
        }

        DistributorAssignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));

        // BUG-L11 fix: an assignment the distributor has already responded
        // to (APPROVED/MODIFIED/REJECTED) is a record of what they actually
        // agreed to, proposed, or declined — hard-deleting it would erase
        // that history with no trace. Only a still-PENDING assignment (i.e.
        // a genuine admin mistake made before any response) may be deleted.
        if (assignment.getStatus() != AssignmentStatus.PENDING) {
            throw new BadRequestException(
                    "Only a pending assignment can be deleted — this assignment has already been "
                            + "responded to and its record should be preserved.");
        }

        assignmentRepository.delete(assignment);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Assignment deleted")
                .data("Deleted")
                .build();
    }
}
