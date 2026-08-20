package com.spartan.dms.mapper;

import com.spartan.dms.dto.SuperStockistRequest;
import com.spartan.dms.dto.SuperStockistResponse;
import com.spartan.dms.entity.SuperStockist;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class SuperStockistMapper {

    private final ModelMapper modelMapper;

    public SuperStockistMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public SuperStockist toEntity(SuperStockistRequest request) {
        return modelMapper.map(request, SuperStockist.class);
    }

    public SuperStockistResponse toResponse(SuperStockist superStockist) {
        return modelMapper.map(superStockist, SuperStockistResponse.class);
    }

    public SuperStockistResponse toResponse(SuperStockist superStockist, long assignedDistributorCount) {
        SuperStockistResponse response = toResponse(superStockist);
        response.setAssignedDistributorCount(assignedDistributorCount);
        return response;
    }

    public void updateEntity(SuperStockistRequest request, SuperStockist superStockist) {
        modelMapper.map(request, superStockist);
    }
}
