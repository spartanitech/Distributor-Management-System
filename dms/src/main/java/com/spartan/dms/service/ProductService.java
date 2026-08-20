package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.ProductRequest;
import com.spartan.dms.dto.ProductResponse;
import com.spartan.dms.entity.Category;
import com.spartan.dms.entity.Product;
import com.spartan.dms.exception.DuplicateResourceException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.exception.FileStorageException;
import com.spartan.dms.mapper.ProductMapper;
import com.spartan.dms.repository.CategoryRepository;
import com.spartan.dms.repository.ProductRepository;
import com.spartan.dms.util.FileUploadUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper productMapper;
    private final com.spartan.dms.repository.InvoiceItemRepository invoiceItemRepository;
    private final com.spartan.dms.repository.WarehouseRepository warehouseRepository;
    private final com.spartan.dms.repository.ProductRequestRepository productRequestRepository;
    private final com.spartan.dms.repository.StockTransferRepository stockTransferRepository;
    private final com.spartan.dms.repository.StockMovementRepository stockMovementRepository;
    private final com.spartan.dms.repository.DistributorAssignmentRepository distributorAssignmentRepository;
    private final com.spartan.dms.repository.ProductSuperStockistPriceRepository productSuperStockistPriceRepository;
    private final com.spartan.dms.repository.ProductDistributorPriceRepository productDistributorPriceRepository;
    private final ProductLedgerService productLedgerService;
    private final com.spartan.dms.repository.ProductLedgerRepository productLedgerRepository;
    private final com.spartan.dms.repository.PurchaseReturnRepository purchaseReturnRepository;
    private final com.spartan.dms.security.SecurityUtils securityUtils;
    private final AuditLogService auditLogService;
    private final FileUploadUtil fileUploadUtil;

    private static final List<String> ALLOWED_IMAGE_CONTENT_TYPES =
            List.of("image/jpeg", "image/jpg", "image/png", "image/webp");
    private static final long MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024; // 5 MB

    /**
     * CREATE — always results in an INSERT.
     *
     * Root-cause fix for the "Row was updated or deleted by another
     * transaction (or unsaved-value mapping was incorrect): Product#1"
     * error: that exception is Hibernate's StaleObjectStateException. It is
     * thrown when save() is called on an entity whose id is already
     * non-null, causing Spring Data JPA to route the call through
     * entityManager.merge() (UPDATE ... WHERE id = ?) instead of
     * persist() (INSERT). If no row with that id exists, 0 rows are
     * updated and Hibernate reports it as a stale/optimistic-lock failure.
     *
     * This method guarantees the entity handed to save() is always
     * transient (id == null) so Spring Data JPA's isNew() check routes it
     * to persist(), which always performs a plain INSERT and lets
     * MySQL's AUTO_INCREMENT / Hibernate's IDENTITY generator assign the id.
     */
    @Transactional
    public ApiResponse<ProductResponse> createProduct(ProductRequest request) {

        if (productRepository.existsByProductCode(request.getProductCode())) {
            throw new DuplicateResourceException("Product Code already exists");
        }

        if (request.getBarcode() != null && !request.getBarcode().isBlank()
                && productRepository.findByBarcode(request.getBarcode()).isPresent()) {
            throw new DuplicateResourceException("Barcode already exists");
        }

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        Product product = productMapper.toEntity(request);

        // Defensive guarantee (belt-and-suspenders on top of the mapper-level
        // TypeMap skip): a product being CREATED must never carry a
        // pre-existing id. This one line is what makes it structurally
        // impossible for this code path to ever throw the stale-object error
        // again, no matter what a future frontend/DTO change sends us.
        product.setId(null);
        product.setCategory(category);

        if (product.getMinimumStock() == null) {
            product.setMinimumStock(0);
        }
        if (product.getActive() == null) {
            product.setActive(true);
        }

        Product saved = productRepository.save(product);
        log.info("Created product id={} code={}", saved.getId(), saved.getProductCode());

        auditLogService.log("CREATE", "PRODUCT", saved.getId(), "Created product " + saved.getProductName());

        // Opening Stock — a product created with a non-zero starting
        // quantity gets exactly one OPENING_STOCK ledger row so the
        // Product Ledger's running balance starts from a real number
        // instead of appearing out of nowhere on the first sale.
        if (saved.getStockQuantity() != null && saved.getStockQuantity() > 0) {
            com.spartan.dms.service.ProductLedgerService.LedgerBuilder lb = new com.spartan.dms.service.ProductLedgerService.LedgerBuilder();
            lb.transactionDateTime = java.time.LocalDateTime.now();
            lb.voucherNo = "OPN-" + saved.getId();
            lb.transactionType = com.spartan.dms.enums.LedgerTransactionType.OPENING_STOCK;
            lb.product = saved;
            lb.ownerType = com.spartan.dms.enums.OwnerType.COMPANY;
            lb.inQuantity = java.math.BigDecimal.valueOf(saved.getStockQuantity());
            lb.outQuantity = java.math.BigDecimal.ZERO;
            lb.unitCost = saved.getPurchasePrice() != null ? saved.getPurchasePrice() : java.math.BigDecimal.ZERO;
            lb.performedBy = currentUsernameOrSystem();
            lb.remarks = "Opening stock at product creation";
            productLedgerService.record(lb);
        }

        return ApiResponse.<ProductResponse>builder()
                .success(true)
                .message("Product Created Successfully")
                .data(productMapper.toResponse(saved))
                .build();
    }

    /** Never throws — falls back to "SYSTEM" outside an authenticated request context. */
    private String currentUsernameOrSystem() {
        try {
            return securityUtils.getCurrentUser().getUsername();
        } catch (RuntimeException ex) {
            return "SYSTEM";
        }
    }

    public ApiResponse<List<ProductResponse>> getAllProducts() {

        List<ProductResponse> products = productRepository.findAll()
                .stream()
                .map(productMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<ProductResponse>>builder()
                .success(true)
                .message("Product List")
                .data(products)
                .build();
    }

    public ApiResponse<ProductResponse> getProductById(Long id) {

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        return ApiResponse.<ProductResponse>builder()
                .success(true)
                .message("Product Details")
                .data(productMapper.toResponse(product))
                .build();
    }

    /**
     * UPDATE — always operates on an entity that Hibernate already knows
     * about (fetched by id from the DB first), so save() legitimately
     * performs an UPDATE. The id used comes only from the URL path
     * variable — never from the request body — which is what keeps
     * create and update cleanly separated.
     */
    @Transactional
    public ApiResponse<ProductResponse> updateProduct(Long id, ProductRequest request) {

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        if (!product.getProductCode().equalsIgnoreCase(request.getProductCode())
                && productRepository.existsByProductCode(request.getProductCode())) {
            throw new DuplicateResourceException("Product Code already exists");
        }

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        // stockQuantity is protected from this call on purpose: it's the
        // single number every Invoice/Warehouse/ProductLedger write path in
        // this codebase depends on staying accurate, and every one of them
        // updates it alongside a matching ledger entry. This "edit product
        // details" endpoint has no ledger-recording of its own, so letting
        // ProductMapper.updateEntity() silently overwrite it here (it maps
        // any field with a matching name, stockQuantity included) meant any
        // product edit that included a stale/different stockQuantity value
        // -- including simply reloading the edit form after a sale changed
        // it, since the frontend's Create/Edit form always resends this
        // field -- corrupted real stock with zero audit trail. Stock
        // changes belong exclusively to updateStock()/recordStockInward()
        // below, which do record it properly.
        Integer existingStockQuantity = product.getStockQuantity();

        // BUG-L3 note: minimumStock is not guarded the same way stockQuantity
        // is above, because it doesn't need to be — ModelMapperConfig has
        // setSkipNullEnabled(true), so productMapper.updateEntity() (a plain
        // ModelMapper.map(request, product) call) already skips any null
        // source field instead of overwriting the destination with null.
        // A partial update that omits minimumStock therefore leaves the
        // existing value on `product` untouched; it can never be nulled out
        // via this path. (The nullable-column edge case this codebase does
        // still have to guard against — legacy rows created before
        // createProduct() started defaulting it to 0 — is handled at the
        // query level in ProductRepository.findLowStockProducts()/
        // countLowStockProducts() via COALESCE(p.minimumStock, 0).)
        productMapper.updateEntity(request, product);
        product.setCategory(category);
        product.setStockQuantity(existingStockQuantity);

        Product saved = productRepository.save(product);
        log.info("Updated product id={} code={}", saved.getId(), saved.getProductCode());

        auditLogService.log("UPDATE", "PRODUCT", saved.getId(), "Updated product " + saved.getProductName());

        return ApiResponse.<ProductResponse>builder()
                .success(true)
                .message("Product Updated Successfully")
                .data(productMapper.toResponse(saved))
                .build();
    }

    /**
     * BUG-H15 fix — product image upload was previously a fully dead
     * feature end-to-end: Product.productImage and ProductResponse.productImage
     * both existed in the entity/DTO, but there was no endpoint, service
     * method, or frontend control that could ever populate the column, so
     * it was permanently null for every product. This method is the
     * missing write path, mirroring PaymentProofService.uploadProof()'s
     * validate -> store -> persist pattern via the same FileUploadUtil.
     *
     * Unlike payment proofs (financial documents scoped to one payment's
     * parties, served through an access-controlled download endpoint),
     * product images are not sensitive/per-tenant data, so the file is
     * stored under uploads/products/ and served directly via the existing
     * /uploads/** static handler (see WebConfig) — no dedicated download
     * endpoint is needed.
     */
    @Transactional
    public ApiResponse<ProductResponse> uploadProductImage(Long id, MultipartFile file) {

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        if (file == null || file.isEmpty()) {
            throw new FileStorageException("Please select a file to upload.");
        }

        FileUploadUtil.StoredFile stored = fileUploadUtil.uploadFile(
                file, "products", ALLOWED_IMAGE_CONTENT_TYPES, MAX_IMAGE_SIZE_BYTES);

        product.setProductImage(stored.publicUrl());
        Product saved = productRepository.save(product);

        auditLogService.log("UPDATE", "PRODUCT", saved.getId(),
                "Updated product image for " + saved.getProductName());

        log.info("Uploaded product image id={} file={}", saved.getId(), stored.relativePath());

        return ApiResponse.<ProductResponse>builder()
                .success(true)
                .message("Product Image Uploaded Successfully")
                .data(productMapper.toResponse(saved))
                .build();
    }

    @Transactional
    public ApiResponse<String> deleteProduct(Long id) {

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        // A product with any sales/stock history can't be hard-deleted
        // without either losing that history or hitting a raw FK
        // violation — block with a clear reason and point at deactivation
        // instead, which is what the Status toggle already supports.
        List<String> blockers = new java.util.ArrayList<>();
        if (invoiceItemRepository.existsByProductId(id)) blockers.add("invoice line items");
        if (warehouseRepository.existsByProductId(id)) blockers.add("warehouse/stock records");
        if (productRequestRepository.existsByProductId(id)) blockers.add("stock requests");
        if (stockTransferRepository.existsByProductId(id)) blockers.add("stock transfers");
        if (stockMovementRepository.existsByProductId(id)) blockers.add("stock movement history");
        if (distributorAssignmentRepository.existsByProductId(id)) blockers.add("distributor assignments");
        if (productSuperStockistPriceRepository.existsByProductId(id)) blockers.add("custom Super Stockist pricing");
        if (productDistributorPriceRepository.existsByProductId(id)) blockers.add("custom Distributor pricing");
        if (productLedgerRepository.existsByProductId(id)) blockers.add("product ledger history");
        if (purchaseReturnRepository.existsByProductId(id)) blockers.add("purchase returns");

        if (!blockers.isEmpty()) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "Cannot delete: this product still has " + String.join(", ", blockers)
                            + ". Set it to Inactive instead of deleting it.");
        }

        productRepository.delete(product);

        auditLogService.log("DELETE", "PRODUCT", id, "Deleted product " + product.getProductName());

        return ApiResponse.<String>builder()
                .success(true)
                .message("Product Deleted Successfully")
                .data("Deleted")
                .build();
    }

    public ApiResponse<List<ProductResponse>> searchProduct(String keyword) {

        List<ProductResponse> products = productRepository
                .findByProductNameContainingIgnoreCase(keyword)
                .stream()
                .map(productMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<ProductResponse>>builder()
                .success(true)
                .message("Search Results")
                .data(products)
                .build();
    }

    public ApiResponse<List<ProductResponse>> getProductsByCategory(Long categoryId) {

        List<ProductResponse> products = productRepository.findByCategoryId(categoryId)
                .stream()
                .map(productMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<ProductResponse>>builder()
                .success(true)
                .message("Products By Category")
                .data(products)
                .build();
    }

    @Transactional
    public ApiResponse<String> updateProductStatus(Long id, Boolean status) {

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        product.setActive(status);
        productRepository.save(product);

        auditLogService.log("STATUS_CHANGE", "PRODUCT", id,
                "Product " + product.getProductName() + " status set to " + status);

        return ApiResponse.success("Status Updated", "Success");
    }

    @Transactional
    public ApiResponse<String> updateStock(Long id, Integer stock) {

        if (stock == null || stock < 0) {
            throw new com.spartan.dms.exception.BadRequestException("Stock quantity cannot be negative");
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        int previous = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        int delta = (stock != null ? stock : 0) - previous;

        product.setStockQuantity(stock);
        productRepository.save(product);

        // Manual Stock Correction — this endpoint blind-overwrites the
        // count, so the ledger records whatever delta that overwrite
        // implies (positive = correction added stock, negative = removed).
        if (delta != 0) {
            com.spartan.dms.service.ProductLedgerService.LedgerBuilder lb = new com.spartan.dms.service.ProductLedgerService.LedgerBuilder();
            lb.transactionDateTime = java.time.LocalDateTime.now();
            lb.voucherNo = "MSC-" + product.getId() + "-" + System.currentTimeMillis();
            lb.transactionType = com.spartan.dms.enums.LedgerTransactionType.MANUAL_STOCK_CORRECTION;
            lb.product = product;
            lb.ownerType = com.spartan.dms.enums.OwnerType.COMPANY;
            lb.inQuantity = delta > 0 ? java.math.BigDecimal.valueOf(delta) : java.math.BigDecimal.ZERO;
            lb.outQuantity = delta < 0 ? java.math.BigDecimal.valueOf(-delta) : java.math.BigDecimal.ZERO;
            lb.unitCost = product.getPurchasePrice() != null ? product.getPurchasePrice() : java.math.BigDecimal.ZERO;
            lb.performedBy = currentUsernameOrSystem();
            lb.remarks = "Manual stock overwrite: " + previous + " -> " + stock;
            productLedgerService.record(lb);
        }

        auditLogService.log("STOCK_UPDATE", "PRODUCT", product.getId(),
                "Stock for " + product.getProductName() + " set from " + previous + " to " + stock);

        return ApiResponse.success("Stock Updated", "Success");
    }

    /**
     * Additive Stock Entry (inward) — adds to current stock instead of
     * overwriting it, and logs a StockMovement ledger row so the Stock
     * Summary report has real Inward history. Defaults the rate to the
     * product's purchase price when the caller doesn't supply one.
     */
    @Transactional
    public ApiResponse<String> recordStockInward(Long id, java.math.BigDecimal quantity,
                                                  java.math.BigDecimal rate, String remarks) {

        if (quantity == null || quantity.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new com.spartan.dms.exception.BadRequestException("Quantity must be greater than zero");
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        java.math.BigDecimal effectiveRate = rate != null ? rate
                : (product.getPurchasePrice() != null ? product.getPurchasePrice() : java.math.BigDecimal.ZERO);

        int current = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        product.setStockQuantity(current + quantity.intValue());
        productRepository.save(product);

        stockMovementRepository.save(com.spartan.dms.entity.StockMovement.builder()
                .product(product)
                .movementType(com.spartan.dms.entity.StockMovement.MovementType.INWARD)
                .quantity(quantity)
                .rate(effectiveRate)
                .value(effectiveRate.multiply(quantity))
                .movementDate(java.time.LocalDate.now())
                .referenceType("STOCK_ENTRY")
                .remarks(remarks)
                .build());

        // Product Ledger — this endpoint is the ad-hoc "add stock" action
        // used for manual corrections until a dedicated Purchase module
        // exists, so it's logged as MANUAL_STOCK_CORRECTION.
        com.spartan.dms.service.ProductLedgerService.LedgerBuilder lb = new com.spartan.dms.service.ProductLedgerService.LedgerBuilder();
        lb.transactionDateTime = java.time.LocalDateTime.now();
        lb.voucherNo = "MSC-" + product.getId() + "-" + System.currentTimeMillis();
        lb.transactionType = com.spartan.dms.enums.LedgerTransactionType.MANUAL_STOCK_CORRECTION;
        lb.product = product;
        lb.ownerType = com.spartan.dms.enums.OwnerType.COMPANY;
        lb.inQuantity = quantity;
        lb.outQuantity = java.math.BigDecimal.ZERO;
        lb.unitCost = effectiveRate;
        lb.performedBy = currentUsernameOrSystem();
        lb.remarks = remarks != null ? remarks : "Manual stock entry";
        productLedgerService.record(lb);

        auditLogService.log("STOCK_INWARD", "PRODUCT", product.getId(),
                "Added " + quantity + " units to " + product.getProductName() + " (stock inward)");

        return ApiResponse.success("Stock added", "Success");
    }

    public ApiResponse<List<ProductResponse>> getLowStockProducts() {

        List<ProductResponse> products = productRepository.findLowStockProducts()
                .stream()
                .map(productMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<ProductResponse>>builder()
                .success(true)
                .message("Low Stock Products")
                .data(products)
                .build();
    }

    /**
     * MRP-wise stock — current on-hand quantity grouped by each distinct
     * MRP price point. Scope depends on the caller: Admin sees the Company
     * root count (Product.stockQuantity, the same counter every other
     * admin-facing stock view in this codebase uses); a Super Stockist or
     * Distributor sees only their own Warehouse on-hand stock, mirroring
     * WarehouseService.getMyWarehouse()'s scoping.
     */
    @Transactional
    public ApiResponse<com.spartan.dms.dto.MrpWiseStockResponse> getMrpWiseStock() {

        java.util.Map<Product, Integer> quantityByProduct = new java.util.LinkedHashMap<>();

        if (securityUtils.isSuperStockist()) {
            for (com.spartan.dms.entity.Warehouse w : warehouseRepository.findBySuperStockistId(securityUtils.getScopedSuperStockistId())) {
                if (w.getProduct() != null && w.getQuantity() != null && w.getQuantity() > 0) {
                    quantityByProduct.merge(w.getProduct(), w.getQuantity(), Integer::sum);
                }
            }
        } else if (securityUtils.isDistributor()) {
            for (com.spartan.dms.entity.Warehouse w : warehouseRepository.findByDistributorId(securityUtils.getScopedDistributorId())) {
                if (w.getProduct() != null && w.getQuantity() != null && w.getQuantity() > 0) {
                    quantityByProduct.merge(w.getProduct(), w.getQuantity(), Integer::sum);
                }
            }
        } else {
            for (Product p : productRepository.findAll()) {
                if (p.getStockQuantity() != null && p.getStockQuantity() > 0) {
                    quantityByProduct.merge(p, p.getStockQuantity(), Integer::sum);
                }
            }
        }

        java.util.Map<java.math.BigDecimal, java.util.List<com.spartan.dms.dto.MrpWiseStockResponse.ProductLine>> byMrp = new java.util.TreeMap<>();
        for (java.util.Map.Entry<Product, Integer> e : quantityByProduct.entrySet()) {
            Product p = e.getKey();
            java.math.BigDecimal mrp = p.getMrp() != null ? p.getMrp() : java.math.BigDecimal.ZERO;
            byMrp.computeIfAbsent(mrp, k -> new java.util.ArrayList<>())
                    .add(com.spartan.dms.dto.MrpWiseStockResponse.ProductLine.builder()
                            .productId(p.getId())
                            .productName(p.getProductName())
                            .productCode(p.getProductCode())
                            .quantity(e.getValue())
                            .build());
        }

        java.util.List<com.spartan.dms.dto.MrpWiseStockResponse.Group> groups = new java.util.ArrayList<>();
        for (java.util.Map.Entry<java.math.BigDecimal, java.util.List<com.spartan.dms.dto.MrpWiseStockResponse.ProductLine>> e : byMrp.entrySet()) {
            int total = e.getValue().stream().mapToInt(com.spartan.dms.dto.MrpWiseStockResponse.ProductLine::getQuantity).sum();
            groups.add(com.spartan.dms.dto.MrpWiseStockResponse.Group.builder()
                    .mrp(e.getKey())
                    .totalQuantity(total)
                    .products(e.getValue())
                    .build());
        }

        return ApiResponse.<com.spartan.dms.dto.MrpWiseStockResponse>builder()
                .success(true)
                .message("MRP-wise Stock")
                .data(com.spartan.dms.dto.MrpWiseStockResponse.builder().groups(groups).build())
                .build();
    }
}