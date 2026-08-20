package com.spartan.dms.mapper;

import com.spartan.dms.dto.ProductRequest;
import com.spartan.dms.dto.ProductResponse;
import com.spartan.dms.entity.Category;
import com.spartan.dms.entity.Product;
import jakarta.annotation.PostConstruct;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    private final ModelMapper modelMapper;
    private final com.spartan.dms.security.SecurityUtils securityUtils;

    public ProductMapper(ModelMapper modelMapper, com.spartan.dms.security.SecurityUtils securityUtils) {
        this.modelMapper = modelMapper;
        this.securityUtils = securityUtils;
    }

    /**
     * Explicit TypeMaps, configured once, that guarantee id / createdAt /
     * updatedAt are NEVER written by DTO -> Entity mapping, regardless of
     * whether someone later adds an "id" field to ProductRequest or renames
     * a property in a way that could accidentally match "id" under a loose
     * matching strategy. This is the mapper-level defense against the
     * StaleObjectStateException root cause described in ProductRequest.
     */
    @PostConstruct
    private void configureMappings() {

        // ProductRequest -> Product (used for CREATE)
        modelMapper.createTypeMap(ProductRequest.class, Product.class)
                .addMappings(mapper -> {
                    mapper.skip(Product::setId);
                    mapper.skip(Product::setCreatedAt);
                    mapper.skip(Product::setUpdatedAt);
                    mapper.skip(Product::setCategory); // set explicitly in service after lookup
                });

        // The single TypeMap above is reused for both create (toEntity,
        // mapping onto a brand-new Product) and update (updateEntity,
        // mapping onto an existing managed Product) — in both flows the
        // service resolves and sets `category` explicitly after mapping,
        // and id/createdAt/updatedAt must never be touched by the DTO.
    }

    public Product toEntity(ProductRequest request) {
        Product product = modelMapper.map(request, Product.class);
        // Belt-and-suspenders: even if a future change to ProductRequest or
        // the TypeMap configuration above regresses, a brand-new entity
        // built from a *create* request must never carry a database id.
        product.setId(null);
        return product;
    }

    public ProductResponse toResponse(Product product) {
        ProductResponse response = modelMapper.map(product, ProductResponse.class);
        Category category = product.getCategory();
        if (category != null) {
            response.setCategoryId(category.getId());
            response.setCategoryName(category.getCategoryName());
        }
        applyPriceVisibility(response);
        return response;
    }

    /**
     * Enforces the 3-tier price confidentiality rule:
     *   - Admin: sees purchasePrice, ssPrice, distributorPrice (DP), sellingPrice (SP) — everything.
     *   - Super Stockist: sees distributorPrice (their own selling rate to Distributors) and
     *     sellingPrice (reference/MRP context) — NEVER ssPrice or purchasePrice (Company's cost to them
     *     stays admin-only) so a Distributor can never learn it even indirectly.
     *   - Distributor (and anyone else, e.g. no role matched): sees ONLY sellingPrice (their own
     *     rate to Shops) — NEVER distributorPrice/ssPrice/purchasePrice. Shops have no login in this
     *     system at all, so they never call this endpoint in the first place.
     */
    private void applyPriceVisibility(ProductResponse response) {
        if (securityUtils.isAdmin()) {
            return;
        }
        if (securityUtils.isSuperStockist()) {
            response.setSsPrice(null);
            response.setPurchasePrice(null);
            return;
        }
        // Distributor (or any other non-admin, non-SS caller)
        response.setSsPrice(null);
        response.setDistributorPrice(null);
        response.setPurchasePrice(null);
    }

    /**
     * Maps changed fields from the request onto an EXISTING, already
     * fetched-and-managed Product entity (update flow only). The entity's
     * id/createdAt are protected by the TypeMap configured above, so this
     * call can never turn into an accidental create-with-forced-id.
     */
    public void updateEntity(ProductRequest request, Product product) {
        modelMapper.map(request, product);
    }
}