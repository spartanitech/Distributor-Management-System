package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.ProductRequestActionDto;
import com.spartan.dms.dto.ProductRequestCreateDto;
import com.spartan.dms.dto.ProductRequestResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.Product;
import com.spartan.dms.entity.ProductRequest;
import com.spartan.dms.entity.StockTransfer;
import com.spartan.dms.entity.SuperStockist;
import com.spartan.dms.entity.User;
import com.spartan.dms.entity.Warehouse;
import com.spartan.dms.enums.NotificationType;
import com.spartan.dms.enums.OwnerType;
import com.spartan.dms.enums.ProductRequestStatus;
import com.spartan.dms.enums.RequestLevel;
import com.spartan.dms.enums.TransferStatus;
import com.spartan.dms.exception.BadRequestException;
import com.spartan.dms.exception.ForbiddenException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.mapper.ProductRequestMapper;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.ProductRepository;
import com.spartan.dms.repository.ProductRequestRepository;
import com.spartan.dms.repository.StockTransferRepository;
import com.spartan.dms.repository.SuperStockistRepository;
import com.spartan.dms.repository.WarehouseRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Backs the full 2-tier stock-request chain:
 *
 *   Distributor --(DISTRIBUTOR_TO_SUPER_STOCKIST)--> their own Super Stockist
 *   Super Stockist --(SUPER_STOCKIST_TO_COMPANY)--> Admin/Company
 *
 * Fulfilling a request actually moves stock: DISTRIBUTOR_TO_SUPER_STOCKIST
 * draws down the Super Stockist's own Warehouse row and credits the
 * Distributor's; SUPER_STOCKIST_TO_COMPANY draws down Product.stockQuantity
 * (the Company root) and credits the Super Stockist's Warehouse row. Both
 * legs write an immutable StockTransfer audit row and notify the requester.
 */
@Service
@RequiredArgsConstructor
public class ProductRequestService {

    private final ProductRequestRepository productRequestRepository;
    private final DistributorRepository distributorRepository;
    private final SuperStockistRepository superStockistRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockTransferRepository stockTransferRepository;
    private final ProductRequestMapper mapper;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;
    private final ProductLedgerService productLedgerService;
    private final AuditLogService auditLogService;

    @Transactional
    public ApiResponse<ProductRequestResponse> createRequest(ProductRequestCreateDto dto) {

        if (dto.getProductId() == null) {
            throw new BadRequestException("productId is required");
        }
        if (dto.getRequestedQuantity() == null || dto.getRequestedQuantity() <= 0) {
            throw new BadRequestException("requestedQuantity must be greater than zero");
        }

        Product product = productRepository.findById(dto.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        ProductRequest pr;

        boolean superStockistRaisingToCompany = securityUtils.isSuperStockist()
                || (securityUtils.isAdmin() && dto.getSuperStockistId() != null && dto.getDistributorId() == null);

        if (superStockistRaisingToCompany) {
            Long superStockistId = securityUtils.isSuperStockist()
                    ? securityUtils.getScopedSuperStockistId()
                    : dto.getSuperStockistId();

            SuperStockist superStockist = superStockistRepository.findById(superStockistId)
                    .orElseThrow(() -> new ResourceNotFoundException("Super Stockist not found"));

            pr = ProductRequest.builder()
                    .requestLevel(RequestLevel.SUPER_STOCKIST_TO_COMPANY)
                    .superStockist(superStockist)
                    .distributor(null)
                    .product(product)
                    .requestedQuantity(dto.getRequestedQuantity())
                    .status(ProductRequestStatus.PENDING)
                    .remarks(dto.getRemarks())
                    .build();

            pr = productRequestRepository.save(pr);

            auditLogService.log("CREATE", "PRODUCT_REQUEST", pr.getId(),
                    "Super Stockist " + superStockist.getSuperStockistName() + " requested "
                            + dto.getRequestedQuantity() + " x " + product.getProductName() + " from Company stock");

            notificationService.notify(null, null,
                    "Stock request from " + superStockist.getSuperStockistName(),
                    superStockist.getSuperStockistName() + " requested " + dto.getRequestedQuantity()
                            + " x " + product.getProductName() + " from Company stock.",
                    NotificationType.SUCCESS.name(), pr.getId());

        } else {
            Long distributorId = securityUtils.isAdmin() ? dto.getDistributorId() : securityUtils.getScopedDistributorId();
            if (distributorId == null) {
                throw new BadRequestException("distributorId is required when an admin raises a request");
            }

            Distributor distributor = distributorRepository.findById(distributorId)
                    .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));

            if (distributor.getSuperStockist() == null) {
                throw new BadRequestException("This distributor has no assigned Super Stockist yet — ask an admin to assign one first.");
            }

            pr = ProductRequest.builder()
                    .requestLevel(RequestLevel.DISTRIBUTOR_TO_SUPER_STOCKIST)
                    .distributor(distributor)
                    .superStockist(distributor.getSuperStockist())
                    .product(product)
                    .requestedQuantity(dto.getRequestedQuantity())
                    .status(ProductRequestStatus.PENDING)
                    .remarks(dto.getRemarks())
                    .build();

            pr = productRequestRepository.save(pr);

            auditLogService.log("CREATE", "PRODUCT_REQUEST", pr.getId(),
                    "Distributor " + distributor.getDistributorName() + " requested "
                            + dto.getRequestedQuantity() + " x " + product.getProductName());

            notificationService.notify(com.spartan.dms.security.SecurityUtils.ROLE_SUPER_STOCKIST, distributor.getSuperStockist().getId(),
                    "Stock request from " + distributor.getDistributorName(),
                    distributor.getDistributorName() + " requested " + dto.getRequestedQuantity()
                            + " x " + product.getProductName() + ".",
                    NotificationType.SUCCESS.name(), pr.getId());
        }

        return ApiResponse.<ProductRequestResponse>builder()
                .success(true)
                .message("Stock request submitted")
                .data(mapper.toResponse(pr))
                .build();
    }

    /**
     * Admin sees every request (optionally filtered by level and/or
     * status); a Super Stockist sees requests where they're the recipient
     * (their distributors' requests) and their own requests to Admin
     * (optionally narrowed to one level via the level param — this is what
     * separates the "Distributor Stock Requests" and "Admin Stock
     * Requests" tabs); a Distributor sees only their own.
     */
    @Transactional(readOnly = true)
    public ApiResponse<List<ProductRequestResponse>> getRequests(ProductRequestStatus statusFilter, RequestLevel levelFilter) {

        List<ProductRequest> requests;

        if (securityUtils.isAdmin()) {
            requests = (levelFilter != null)
                    ? productRequestRepository.findByRequestLevel(levelFilter)
                    : productRequestRepository.findAllWithDetails();
        } else if (securityUtils.isSuperStockist()) {
            Long superStockistId = securityUtils.getScopedSuperStockistId();
            requests = (levelFilter != null)
                    ? productRequestRepository.findBySuperStockistIdAndRequestLevel(superStockistId, levelFilter)
                    : productRequestRepository.findBySuperStockistId(superStockistId);
        } else {
            Long distributorId = securityUtils.getScopedDistributorId();
            requests = productRequestRepository.findByDistributorId(distributorId);
            if (levelFilter != null) {
                requests = requests.stream().filter(r -> r.getRequestLevel() == levelFilter).collect(Collectors.toList());
            }
        }

        // Status is applied on top of whatever the role/level query already
        // scoped, for EVERY role including Admin — level and status must be
        // combinable (e.g. "SS->Company requests that are Rejected"), not
        // mutually exclusive.
        if (statusFilter != null) {
            requests = requests.stream().filter(r -> r.getStatus() == statusFilter).collect(Collectors.toList());
        }

        List<ProductRequestResponse> responses = requests.stream()
                .map(mapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<ProductRequestResponse>>builder()
                .success(true)
                .message("Product requests fetched")
                .data(responses)
                .build();
    }

    @Transactional(readOnly = true)
    public ApiResponse<ProductRequestResponse> getRequestById(Long id) {

        ProductRequest pr = productRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product request not found"));

        assertAccess(pr);

        return ApiResponse.<ProductRequestResponse>builder()
                .success(true)
                .message("Product request fetched")
                .data(mapper.toResponse(pr))
                .build();
    }

    /**
     * The requester (a Distributor on their own DISTRIBUTOR_TO_SUPER_STOCKIST
     * request, or a Super Stockist on their own SUPER_STOCKIST_TO_COMPANY
     * request) may cancel it while still PENDING.
     */
    @Transactional
    public ApiResponse<ProductRequestResponse> cancelRequest(Long id) {

        ProductRequest pr = productRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product request not found"));

        assertAccess(pr);

        if (pr.getStatus() != ProductRequestStatus.PENDING) {
            throw new BadRequestException("Only a pending request can be cancelled");
        }

        pr.setStatus(ProductRequestStatus.CANCELLED);
        pr = productRequestRepository.save(pr);

        auditLogService.log("CANCEL", "PRODUCT_REQUEST", pr.getId(), "Cancelled product request #" + pr.getId());

        return ApiResponse.<ProductRequestResponse>builder()
                .success(true)
                .message("Product request cancelled")
                .data(mapper.toResponse(pr))
                .build();
    }

    /**
     * Approve/reject/fulfill. DISTRIBUTOR_TO_SUPER_STOCKIST is actioned by
     * Admin or the owning Super Stockist; SUPER_STOCKIST_TO_COMPANY is
     * Admin-only. Fulfilling actually moves stock (see class javadoc).
     */
    @Transactional
    public ApiResponse<ProductRequestResponse> actionRequest(Long id, ProductRequestActionDto dto) {

        ProductRequest pr = productRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product request not found"));

        if (pr.getRequestLevel() == RequestLevel.SUPER_STOCKIST_TO_COMPANY) {
            if (!securityUtils.isAdmin()) {
                throw new ForbiddenException("Only an admin can action a Super Stockist -> Company request");
            }
        } else {
            if (!securityUtils.isAdmin()) {
                securityUtils.assertSuperStockistAccess(pr.getSuperStockist() != null ? pr.getSuperStockist().getId() : null);
            }
        }

        if (pr.getStatus() == ProductRequestStatus.CANCELLED
                || pr.getStatus() == ProductRequestStatus.FULFILLED
                || pr.getStatus() == ProductRequestStatus.REJECTED) {
            throw new BadRequestException("This request has already been finalized (" + pr.getStatus() + ")");
        }

        if (dto.getStatus() == null) {
            throw new BadRequestException("status is required");
        }

        pr.setStatus(dto.getStatus());
        pr.setAdminRemarks(dto.getAdminRemarks());

        if (dto.getStatus() == ProductRequestStatus.APPROVED || dto.getStatus() == ProductRequestStatus.FULFILLED) {
            pr.setApprovedQuantity(
                    dto.getApprovedQuantity() != null ? dto.getApprovedQuantity() : pr.getRequestedQuantity());
        }

        User currentUser = securityUtils.getCurrentUser();
        pr.setActionedBy(currentUser.getUsername());
        pr.setActionedAt(LocalDateTime.now());

        if (dto.getStatus() == ProductRequestStatus.FULFILLED) {
            fulfill(pr, currentUser.getUsername());
        }

        pr = productRequestRepository.save(pr);

        auditLogService.log(dto.getStatus().name(), "PRODUCT_REQUEST", pr.getId(),
                "Product request #" + pr.getId() + " (" + pr.getProduct().getProductName() + ") "
                        + dto.getStatus().name().toLowerCase() + " by " + currentUser.getUsername());

        notifyOnAction(pr);

        return ApiResponse.<ProductRequestResponse>builder()
                .success(true)
                .message("Product request updated")
                .data(mapper.toResponse(pr))
                .build();
    }

    // ---- internal helpers ----

    private void fulfill(ProductRequest pr, String actorUsername) {

        int qty = pr.getApprovedQuantity();
        Product product = pr.getProduct();

        if (pr.getRequestLevel() == RequestLevel.SUPER_STOCKIST_TO_COMPANY) {

            // Draw down the Company root stock...
            if (product.getStockQuantity() == null || product.getStockQuantity() < qty) {
                throw new BadRequestException("Insufficient Company stock to fulfill this request");
            }
            product.setStockQuantity(product.getStockQuantity() - qty);
            productRepository.save(product);

            // ...and credit the Super Stockist's own warehouse.
            SuperStockist superStockist = pr.getSuperStockist();
            Warehouse warehouse = warehouseRepository
                    .findBySuperStockistIdAndProductId(superStockist.getId(), product.getId())
                    .orElseGet(() -> Warehouse.builder()
                            .ownerType(OwnerType.SUPER_STOCKIST)
                            .superStockist(superStockist)
                            .product(product)
                            .quantity(0)
                            .build());
            warehouse.setQuantity(warehouse.getQuantity() + qty);
            warehouseRepository.save(warehouse);

            stockTransferRepository.save(StockTransfer.builder()
                    .fromType(OwnerType.COMPANY)
                    .toType(OwnerType.SUPER_STOCKIST)
                    .toSuperStockist(superStockist)
                    .product(product)
                    .quantity(qty)
                    .transferDate(LocalDateTime.now())
                    .transferredBy(actorUsername)
                    .status(TransferStatus.COMPLETED)
                    .remarks("Fulfilled stock request #" + pr.getId())
                    .build());

            java.math.BigDecimal unitCost = product.getPurchasePrice() != null
                    ? product.getPurchasePrice() : java.math.BigDecimal.ZERO;
            String voucherNo = "ST-" + pr.getId();

            // OUT at Company (the source pool this leg draws down).
            ProductLedgerService.LedgerBuilder out = new ProductLedgerService.LedgerBuilder();
            out.transactionDateTime = LocalDateTime.now();
            out.voucherNo = voucherNo;
            out.transactionType = com.spartan.dms.enums.LedgerTransactionType.STOCK_TRANSFER_OUT;
            out.product = product;
            out.ownerType = OwnerType.COMPANY;
            out.inQuantity = java.math.BigDecimal.ZERO;
            out.outQuantity = java.math.BigDecimal.valueOf(qty);
            out.unitCost = unitCost;
            out.performedBy = actorUsername;
            out.remarks = "Stock transfer to Super Stockist " + superStockist.getSuperStockistName()
                    + " (request #" + pr.getId() + ")";
            productLedgerService.record(out);

            // IN at the Super Stockist's own warehouse.
            ProductLedgerService.LedgerBuilder in = new ProductLedgerService.LedgerBuilder();
            in.transactionDateTime = LocalDateTime.now();
            in.voucherNo = voucherNo;
            in.transactionType = com.spartan.dms.enums.LedgerTransactionType.STOCK_TRANSFER_IN;
            in.product = product;
            in.ownerType = OwnerType.SUPER_STOCKIST;
            in.superStockist = superStockist;
            in.inQuantity = java.math.BigDecimal.valueOf(qty);
            in.outQuantity = java.math.BigDecimal.ZERO;
            in.unitCost = unitCost;
            in.performedBy = actorUsername;
            in.remarks = "Stock transfer from Company (request #" + pr.getId() + ")";
            productLedgerService.record(in);

            auditLogService.log("FULFILL", "PRODUCT_REQUEST", pr.getId(),
                    "Fulfilled request #" + pr.getId() + ": moved " + qty + " x " + product.getProductName()
                            + " from Company stock to Super Stockist " + superStockist.getSuperStockistName());

        } else {

            // Draw down the Super Stockist's own warehouse...
            SuperStockist superStockist = pr.getSuperStockist();
            Warehouse ssWarehouse = warehouseRepository
                    .findBySuperStockistIdAndProductId(superStockist.getId(), product.getId())
                    .orElseThrow(() -> new BadRequestException(
                            "Insufficient stock in your warehouse for this product. Raise a request to Admin first."));

            if (ssWarehouse.getQuantity() < qty) {
                throw new BadRequestException(
                        "Insufficient stock in your warehouse (" + ssWarehouse.getQuantity()
                                + " available). Raise a request to Admin first.");
            }
            ssWarehouse.setQuantity(ssWarehouse.getQuantity() - qty);
            warehouseRepository.save(ssWarehouse);

            // ...and credit the Distributor's warehouse.
            Distributor distributor = pr.getDistributor();
            Warehouse distWarehouse = warehouseRepository
                    .findByDistributorIdAndProductId(distributor.getId(), product.getId())
                    .orElseGet(() -> Warehouse.builder()
                            .ownerType(OwnerType.DISTRIBUTOR)
                            .distributor(distributor)
                            .product(product)
                            .quantity(0)
                            .build());
            distWarehouse.setQuantity(distWarehouse.getQuantity() + qty);
            warehouseRepository.save(distWarehouse);

            stockTransferRepository.save(StockTransfer.builder()
                    .fromType(OwnerType.SUPER_STOCKIST)
                    .fromSuperStockist(superStockist)
                    .toType(OwnerType.DISTRIBUTOR)
                    .toDistributor(distributor)
                    .product(product)
                    .quantity(qty)
                    .transferDate(LocalDateTime.now())
                    .transferredBy(actorUsername)
                    .status(TransferStatus.COMPLETED)
                    .remarks("Fulfilled stock request #" + pr.getId())
                    .build());

            java.math.BigDecimal unitCost2 = product.getPurchasePrice() != null
                    ? product.getPurchasePrice() : java.math.BigDecimal.ZERO;
            String voucherNo2 = "ST-" + pr.getId();

            // OUT at the Super Stockist's warehouse (the source pool this leg draws down).
            ProductLedgerService.LedgerBuilder out2 = new ProductLedgerService.LedgerBuilder();
            out2.transactionDateTime = LocalDateTime.now();
            out2.voucherNo = voucherNo2;
            out2.transactionType = com.spartan.dms.enums.LedgerTransactionType.STOCK_TRANSFER_OUT;
            out2.product = product;
            out2.ownerType = OwnerType.SUPER_STOCKIST;
            out2.superStockist = superStockist;
            out2.inQuantity = java.math.BigDecimal.ZERO;
            out2.outQuantity = java.math.BigDecimal.valueOf(qty);
            out2.unitCost = unitCost2;
            out2.performedBy = actorUsername;
            out2.remarks = "Stock transfer to Distributor " + distributor.getDistributorName()
                    + " (request #" + pr.getId() + ")";
            productLedgerService.record(out2);

            // IN at the Distributor's own warehouse.
            ProductLedgerService.LedgerBuilder in2 = new ProductLedgerService.LedgerBuilder();
            in2.transactionDateTime = LocalDateTime.now();
            in2.voucherNo = voucherNo2;
            in2.transactionType = com.spartan.dms.enums.LedgerTransactionType.STOCK_TRANSFER_IN;
            in2.product = product;
            in2.ownerType = OwnerType.DISTRIBUTOR;
            in2.distributor = distributor;
            in2.inQuantity = java.math.BigDecimal.valueOf(qty);
            in2.outQuantity = java.math.BigDecimal.ZERO;
            in2.unitCost = unitCost2;
            in2.performedBy = actorUsername;
            in2.remarks = "Stock transfer from Super Stockist " + superStockist.getSuperStockistName()
                    + " (request #" + pr.getId() + ")";
            productLedgerService.record(in2);

            auditLogService.log("FULFILL", "PRODUCT_REQUEST", pr.getId(),
                    "Fulfilled request #" + pr.getId() + ": moved " + qty + " x " + product.getProductName()
                            + " from Super Stockist " + superStockist.getSuperStockistName()
                            + " to Distributor " + distributor.getDistributorName());
        }
    }

    private void notifyOnAction(ProductRequest pr) {
        String verb = switch (pr.getStatus()) {
            case APPROVED -> "approved";
            case REJECTED -> "rejected";
            case FULFILLED -> "fulfilled and dispatched";
            default -> pr.getStatus().name().toLowerCase();
        };
        String productName = pr.getProduct() != null ? pr.getProduct().getProductName() : "product";

        if (pr.getRequestLevel() == RequestLevel.SUPER_STOCKIST_TO_COMPANY) {
            if (pr.getSuperStockist() != null) {
                notificationService.notify(com.spartan.dms.security.SecurityUtils.ROLE_SUPER_STOCKIST, pr.getSuperStockist().getId(),
                        "Your stock request was " + verb,
                        "Your request for " + pr.getRequestedQuantity() + " x " + productName + " was " + verb + ".",
                        NotificationType.SUCCESS.name(), pr.getId());
            }
        } else if (pr.getDistributor() != null) {
            notificationService.notify(com.spartan.dms.security.SecurityUtils.ROLE_DISTRIBUTOR, pr.getDistributor().getId(),
                    "Your stock request was " + verb,
                    "Your request for " + pr.getRequestedQuantity() + " x " + productName + " was " + verb + ".",
                    NotificationType.SUCCESS.name(), pr.getId());
        }
    }

    private void assertAccess(ProductRequest pr) {
        if (securityUtils.isAdmin()) {
            return;
        }
        if (securityUtils.isSuperStockist()) {
            Long myId = securityUtils.getScopedSuperStockistId();
            if (pr.getSuperStockist() != null && pr.getSuperStockist().getId().equals(myId)) {
                return;
            }
            throw new ForbiddenException("You do not have access to this stock request");
        }
        Long myDistributorId = securityUtils.getScopedDistributorId();
        if (pr.getDistributor() != null && pr.getDistributor().getId().equals(myDistributorId)) {
            return;
        }
        throw new ForbiddenException("You do not have access to this stock request");
    }
}
