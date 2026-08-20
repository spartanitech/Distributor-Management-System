package com.spartan.dms.mapper;

import com.spartan.dms.dto.DistributorRequest;
import com.spartan.dms.dto.DistributorResponse;
import com.spartan.dms.entity.Distributor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class DistributorMapper {

    private final ModelMapper modelMapper;

    public DistributorMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public Distributor toEntity(DistributorRequest request) {
        return modelMapper.map(request, Distributor.class);
    }

    public DistributorResponse toResponse(Distributor distributor) {
        DistributorResponse response = modelMapper.map(distributor, DistributorResponse.class);
        if (distributor.getSuperStockist() != null) {
            response.setSuperStockistId(distributor.getSuperStockist().getId());
            response.setSuperStockistName(distributor.getSuperStockist().getSuperStockistName());
        }
        return response;
    }

    public void updateEntity(DistributorRequest request, Distributor distributor) {
        modelMapper.map(request, distributor);
    }
}