package com.spartan.dms.mapper;

import com.spartan.dms.dto.ShopRequest;
import com.spartan.dms.dto.ShopResponse;
import com.spartan.dms.entity.Shop;
import org.springframework.stereotype.Component;

/**
 * Deliberately does NOT use ModelMapper (or any reflection-based generic mapper)
 * for Shop. ModelMapper's default matching strategy can silently "flatten" a
 * property like request.distributorId onto shop.distributor.id and, depending
 * on configuration, can be tricked into touching the id/version fields it
 * inherits from BaseEntity. Manual mapping is fully deterministic:
 *  - toEntity() NEVER sets an id -> every created Shop is transient (id == null),
 *    which is what tells Hibernate/JpaRepository to INSERT instead of merge/UPDATE.
 *  - updateEntity() NEVER touches the id field of the managed entity passed in.
 */
@Component
public class ShopMapper {

    public Shop toEntity(ShopRequest request) {
        // Brand new, transient entity. id, createdAt, updatedAt are intentionally
        // left untouched (null) so Hibernate generates them / assigns via IDENTITY.
        Shop shop = new Shop();
        applyRequest(request, shop);
        return shop;
    }

    public void updateEntity(ShopRequest request, Shop shop) {
        // 'shop' here is an already-managed entity loaded by ShopService via
        // shopRepository.findById(id) - its id is preserved because we simply
        // never call shop.setId(...) anywhere in this method.
        applyRequest(request, shop);
    }

    private void applyRequest(ShopRequest request, Shop shop) {
        shop.setShopName(request.getShopName());
        shop.setOwnerName(request.getOwnerName());
        shop.setMobileNumber(request.getMobileNumber());
        shop.setEmail(request.getEmail());
        shop.setAddress(request.getAddress());
        shop.setCity(request.getCity());
        shop.setState(request.getState());
        shop.setDistrict(request.getDistrict());
        shop.setPincode(request.getPincode());
        shop.setGstNumber(request.getGstNumber());
        shop.setRemarks(request.getRemarks());
        shop.setActive(request.getActive() != null ? request.getActive() : Boolean.TRUE);
        // Note: distributor is intentionally NOT set here - ShopService resolves
        // the managed Distributor via distributorRepository.findById(...) and
        // calls shop.setDistributor(distributor) itself, so we never risk
        // attaching a half-populated/transient Distributor instance.
    }

    public ShopResponse toResponse(Shop shop) {
        return ShopResponse.builder()
                .id(shop.getId())
                .shopName(shop.getShopName())
                .ownerName(shop.getOwnerName())
                .mobileNumber(shop.getMobileNumber())
                .email(shop.getEmail())
                .address(shop.getAddress())
                .city(shop.getCity())
                .state(shop.getState())
                .district(shop.getDistrict())
                .pincode(shop.getPincode())
                .gstNumber(shop.getGstNumber())
                .distributorId(shop.getDistributor() != null ? shop.getDistributor().getId() : null)
                .distributorName(shop.getDistributor() != null ? shop.getDistributor().getDistributorName() : null)
                .active(shop.getActive())
                .remarks(shop.getRemarks())
                .createdAt(shop.getCreatedAt())
                .updatedAt(shop.getUpdatedAt())
                .build();
    }
}