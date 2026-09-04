package com.spartan.dms.mapper;

import com.spartan.dms.dto.InvoiceRequest;
import com.spartan.dms.dto.InvoiceResponse;
import com.spartan.dms.entity.Invoice;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class InvoiceMapper {

    private final ModelMapper modelMapper;
    private final com.spartan.dms.repository.InvoiceItemRepository invoiceItemRepository;

    public InvoiceMapper(ModelMapper modelMapper, com.spartan.dms.repository.InvoiceItemRepository invoiceItemRepository) {
        this.modelMapper = modelMapper;
        this.invoiceItemRepository = invoiceItemRepository;
    }

    public Invoice toEntity(InvoiceRequest request) {
        Invoice invoice = modelMapper.map(request, Invoice.class);
        invoice.setInvoiceLevel(parseLevel(request.getInvoiceLevel()));
        return invoice;
    }

    public InvoiceResponse toResponse(Invoice invoice) {

        InvoiceResponse response = modelMapper.map(invoice, InvoiceResponse.class);
        response.setInvoiceLevel(invoice.getInvoiceLevel() != null ? invoice.getInvoiceLevel().name() : null);

        if (invoice.getShop() != null) {
            response.setShopId(invoice.getShop().getId());
            response.setShopName(invoice.getShop().getShopName());
        }

        if (invoice.getDistributor() != null) {
            response.setDistributorId(invoice.getDistributor().getId());
            response.setDistributorName(invoice.getDistributor().getDistributorName());
        }

        if (invoice.getSuperStockist() != null) {
            response.setSuperStockistId(invoice.getSuperStockist().getId());
            response.setSuperStockistName(invoice.getSuperStockist().getSuperStockistName());
        }

        // NOTE: this DTO field existed but was never populated before —
        // every invoice response silently had items=null. Fetches with the
        // product join already batched per-invoice (findByInvoiceIdWithProduct),
        // so this is one extra query per invoice on list endpoints; acceptable
        // for this app's current scale, but worth batching across the whole
        // list in InvoiceService if invoice volumes grow much larger.
        java.util.List<com.spartan.dms.entity.InvoiceItem> items = invoice.getId() != null
                ? invoiceItemRepository.findByInvoiceIdWithProduct(invoice.getId())
                : java.util.Collections.emptyList();
        response.setItems(items.stream().map(item -> InvoiceResponse.InvoiceItemResponse.builder()
                .id(item.getId())
                .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                .productName(item.getProduct() != null ? item.getProduct().getProductName() : null)
                .productCode(item.getProduct() != null ? item.getProduct().getProductCode() : null)
                .hsnSacCode(item.getProduct() != null ? item.getProduct().getHsnSacCode() : null)
                .mrp(item.getProduct() != null ? item.getProduct().getMrp() : null)
                .unit(item.getProduct() != null ? item.getProduct().getUnit() : null)
                .quantity(item.getQuantity())
                .shippedQuantity(item.getShippedQuantity())
                .unitPrice(item.getUnitPrice())
                .discountAmount(item.getDiscountAmount())
                .gstPercentage(item.getGstPercentage())
                .gstAmount(item.getGstAmount())
                .totalAmount(item.getTotalAmount())
                .build()).collect(java.util.stream.Collectors.toList()));

        return response;
    }

    public void updateEntity(InvoiceRequest request, Invoice invoice) {
        modelMapper.map(request, invoice);
        if (request.getInvoiceLevel() != null && !request.getInvoiceLevel().isBlank()) {
            invoice.setInvoiceLevel(parseLevel(request.getInvoiceLevel()));
        }
    }

    private com.spartan.dms.enums.InvoiceLevel parseLevel(String level) {
        if (level == null || level.isBlank()) {
            return com.spartan.dms.enums.InvoiceLevel.DISTRIBUTOR_TO_SHOP;
        }
        try {
            return com.spartan.dms.enums.InvoiceLevel.valueOf(level.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.spartan.dms.exception.BadRequestException("Invalid invoiceLevel: " + level);
        }
    }
}