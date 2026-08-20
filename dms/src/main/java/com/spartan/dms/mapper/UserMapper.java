package com.spartan.dms.mapper;

import com.spartan.dms.dto.UserRequest;
import com.spartan.dms.dto.UserResponse;
import com.spartan.dms.entity.User;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;


@Component
public class UserMapper {

    private final ModelMapper modelMapper;

    public UserMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public User toEntity(UserRequest request) {
        return modelMapper.map(request, User.class);
    }

    public UserResponse toResponse(User user) {
        UserResponse response = modelMapper.map(user, UserResponse.class);
        // ModelMapper's default matching can't flatten an entity relation
        // (user.role -> Role) onto a String destination field just because
        // the names line up — it silently leaves response.role null instead
        // of erroring, which meant every screen that filters by role
        // (Registration Approval, Users role filter, etc.) saw nothing.
        response.setRole(user.getRole() != null ? user.getRole().getRoleName() : null);
        if (user.getDistributor() != null) {
            response.setDistributorId(user.getDistributor().getId());
            response.setDistributorName(user.getDistributor().getDistributorName());
        }
        if (user.getSuperStockist() != null) {
            response.setSuperStockistId(user.getSuperStockist().getId());
            response.setSuperStockistName(user.getSuperStockist().getSuperStockistName());
        }
        response.setApprovalStatus(user.getApprovalStatus() != null ? user.getApprovalStatus().name() : null);
        return response;
    }
    public void updateEntity(UserRequest request, User user) {

        user.setFullName(request.getFullName());
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setMobileNumber(request.getMobileNumber());
        user.setActive(request.getActive());

    }
}