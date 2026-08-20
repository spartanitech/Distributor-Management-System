package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.ShopRequest;
import com.spartan.dms.dto.ShopResponse;
import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.Shop;
import com.spartan.dms.exception.DuplicateResourceException;
import com.spartan.dms.exception.ResourceNotFoundException;
import com.spartan.dms.mapper.ShopMapper;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.ShopRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShopService {

    private final ShopRepository shopRepository;
    private final DistributorRepository distributorRepository;
    private final com.spartan.dms.repository.InvoiceRepository invoiceRepository;
    private final com.spartan.dms.repository.DistributorAssignmentRepository distributorAssignmentRepository;
    private final ShopMapper shopMapper;
    private final SecurityUtils securityUtils;
    private final AuditLogService auditLogService;

    public ApiResponse<ShopResponse> createShop(ShopRequest request) {

        // A distributor can create a shop for their own account only; admin
        // can create for any distributor. assertDistributorAccess is a
        // no-op for admins, throws if a distributor targets someone else.
        securityUtils.assertDistributorAccess(request.getDistributorId());

        if (shopRepository.existsByMobileNumber(request.getMobileNumber())) {
            throw new DuplicateResourceException("Mobile Number already exists");
        }

        if (request.getEmail() != null && !request.getEmail().isBlank()
                && shopRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already exists");
        }

        if (request.getGstNumber() != null && !request.getGstNumber().isBlank()
                && shopRepository.existsByGstNumber(request.getGstNumber())) {
            throw new DuplicateResourceException("GST Number already exists");
        }

        Distributor distributor = distributorRepository.findById(request.getDistributorId())
                .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));

        // shopMapper.toEntity() always returns a brand-new `new Shop()` instance -
        // never an entity fetched/loaded from the database. ShopRequest has no id
        // field at all, so there is nothing to accidentally propagate here.
        // The explicit setId(null) below is a belt-and-braces guard: it guarantees
        // shop.getId() == null at save time, which is what makes Spring Data JPA's
        // SimpleJpaRepository.save() call entityManager.persist() (INSERT) instead
        // of entityManager.merge() (UPDATE). This is what prevents the
        // "Row was updated or deleted by another transaction (or unsaved-value
        // mapping was incorrect)" exception, which only happens when save() is
        // called on an entity that already carries a non-null id for a row that
        // Hibernate can't find/lock.
        Shop shop = shopMapper.toEntity(request);
        shop.setId(null);
        shop.setDistributor(distributor);

        shop = shopRepository.save(shop);

        auditLogService.log("CREATE", "SHOP", shop.getId(), "Created shop " + shop.getShopName());

        return ApiResponse.<ShopResponse>builder()
                .success(true)
                .message("Shop Created Successfully")
                .data(shopMapper.toResponse(shop))
                .build();
    }

    public ApiResponse<List<ShopResponse>> getAllShops() {

        List<Shop> shopEntities;

        if (securityUtils.isSuperStockist()) {
            shopEntities = shopRepository.findByDistributor_SuperStockist_Id(securityUtils.getScopedSuperStockistId());
        } else {
            Long scopedDistributorId = securityUtils.getScopedDistributorId();
            shopEntities = (scopedDistributorId != null)
                    ? shopRepository.findByDistributorId(scopedDistributorId)
                    : shopRepository.findAll();
        }

        List<ShopResponse> shops = shopEntities
                .stream()
                .map(shopMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<ShopResponse>>builder()
                .success(true)
                .message("Shop List")
                .data(shops)
                .build();
    }

    public ApiResponse<ShopResponse> getShopById(Long id) {

        Shop shop = shopRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));

        securityUtils.assertDistributorAccess(shop.getDistributor().getId());

        return ApiResponse.<ShopResponse>builder()
                .success(true)
                .message("Shop Details")
                .data(shopMapper.toResponse(shop))
                .build();
    }

    public ApiResponse<ShopResponse> updateShop(Long id, ShopRequest request) {

        // 'shop' is the MANAGED entity for this exact id, loaded in this
        // transaction. Its id is never reassigned - shopMapper.updateEntity()
        // only mutates the non-id fields on this same object, so save() below
        // is always an UPDATE of this specific row, never an INSERT.
        Shop shop = shopRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));

        // A distributor may edit only their own shop, and can't reassign it
        // to another distributor. Admin can edit/reassign freely.
        securityUtils.assertDistributorAccess(shop.getDistributor().getId());
        securityUtils.assertDistributorAccess(request.getDistributorId());

        Distributor distributor = distributorRepository.findById(request.getDistributorId())
                .orElseThrow(() -> new ResourceNotFoundException("Distributor not found"));

        if (request.getMobileNumber() != null
                && shopRepository.existsByMobileNumberAndIdNot(request.getMobileNumber(), id)) {
            throw new DuplicateResourceException("Mobile Number already exists");
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()
                && shopRepository.existsByEmailAndIdNot(request.getEmail(), id)) {
            throw new DuplicateResourceException("Email already exists");
        }
        if (request.getGstNumber() != null && !request.getGstNumber().isBlank()
                && shopRepository.existsByGstNumberAndIdNot(request.getGstNumber(), id)) {
            throw new DuplicateResourceException("GST Number already exists");
        }

        shopMapper.updateEntity(request, shop);
        shop.setDistributor(distributor);

        shop = shopRepository.save(shop);

        return ApiResponse.<ShopResponse>builder()
                .success(true)
                .message("Shop Updated Successfully")
                .data(shopMapper.toResponse(shop))
                .build();
    }

    public ApiResponse<String> deleteShop(Long id) {

        if (!securityUtils.isAdmin()) {
            throw new com.spartan.dms.exception.ForbiddenException("Only an admin can delete shops");
        }

        Shop shop = shopRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));

        if (invoiceRepository.existsByShopId(id)) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "Cannot delete: this shop still has invoices on record. Set it to Inactive instead of deleting it.");
        }
        if (distributorAssignmentRepository.existsByShopId(id)) {
            throw new com.spartan.dms.exception.BadRequestException(
                    "Cannot delete: this shop still has distributor assignments on record. Set it to Inactive instead of deleting it.");
        }

        shopRepository.delete(shop);

        auditLogService.log("DELETE", "SHOP", id, "Deleted shop " + shop.getShopName());

        return ApiResponse.<String>builder()
                .success(true)
                .message("Shop Deleted Successfully")
                .data("Deleted")
                .build();
    }
    public ApiResponse<List<ShopResponse>> searchShop(String keyword) {

        Long scopedDistributorId = securityUtils.isSuperStockist() ? null : securityUtils.getScopedDistributorId();
        Long scopedSuperStockistId = securityUtils.isSuperStockist() ? securityUtils.getScopedSuperStockistId() : null;

        List<ShopResponse> shops = shopRepository.findByShopNameContainingIgnoreCase(keyword)
                .stream()
                .filter(shop -> {
                    if (scopedSuperStockistId != null) {
                        return shop.getDistributor() != null && shop.getDistributor().getSuperStockist() != null
                                && scopedSuperStockistId.equals(shop.getDistributor().getSuperStockist().getId());
                    }
                    return scopedDistributorId == null
                            || (shop.getDistributor() != null && scopedDistributorId.equals(shop.getDistributor().getId()));
                })
                .map(shopMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<ShopResponse>>builder()
                .success(true)
                .message("Shop Search Result")
                .data(shops)
                .build();
    }

    public ApiResponse<List<ShopResponse>> getShopsByDistributor(Long distributorId) {

        securityUtils.assertDistributorAccess(distributorId);

        List<ShopResponse> shops = shopRepository.findByDistributorId(distributorId)
                .stream()
                .map(shopMapper::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.<List<ShopResponse>>builder()
                .success(true)
                .message("Distributor Shop List")
                .data(shops)
                .build();
    }

    public ApiResponse<String> updateShopStatus(Long id, Boolean status) {

        if (!securityUtils.isAdmin()) {
            throw new com.spartan.dms.exception.ForbiddenException("Only an admin can change shop status");
        }

        Shop shop = shopRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));

        shop.setActive(status);

        shopRepository.save(shop);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Shop Status Updated Successfully")
                .data("Success")
                .build();
    }
}