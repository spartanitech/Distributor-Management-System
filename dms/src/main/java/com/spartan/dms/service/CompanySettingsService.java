package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.CompanySettingsRequest;
import com.spartan.dms.dto.CompanySettingsResponse;
import com.spartan.dms.entity.CompanySettings;
import com.spartan.dms.exception.ForbiddenException;
import com.spartan.dms.repository.CompanySettingsRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompanySettingsService {

    // Always the same row — one company, one letterhead. Loaded lazily so
    // a fresh database doesn't need a migration/seed script: the first
    // read (or write) creates it with the details already used on
    // invoices before this settings screen existed, so nothing changes
    // for anyone until Admin explicitly edits them.
    private static final Long SINGLETON_ID = 1L;

    private final CompanySettingsRepository companySettingsRepository;
    private final SecurityUtils securityUtils;

    @Transactional
    public CompanySettings getOrCreate() {
        return companySettingsRepository.findById(SINGLETON_ID)
                .orElseGet(() -> companySettingsRepository.save(defaultSettings()));
    }

    private CompanySettings defaultSettings() {
        return CompanySettings.builder()
                .companyName("SPARTAN CAPITAL ENTERPRISES")
                .address("16/1, Kochadai Main Road, Virudhachalam Street")
                .city("Madurai")
                .state("Tamil Nadu")
                .pincode("625016")
                .gstNumber("33DKIPM3414H2ZB")
                .fssaiNumber("12425012000650")
                .build();
    }

    public ApiResponse<CompanySettingsResponse> get() {
        return ApiResponse.<CompanySettingsResponse>builder()
                .success(true)
                .message("Company Settings")
                .data(toResponse(getOrCreate()))
                .build();
    }

    @Transactional
    public ApiResponse<CompanySettingsResponse> update(CompanySettingsRequest request) {
        if (!securityUtils.isAdmin()) {
            throw new ForbiddenException("Only an admin can update company settings");
        }

        CompanySettings settings = getOrCreate();
        settings.setCompanyName(request.getCompanyName());
        settings.setAddress(request.getAddress());
        settings.setCity(request.getCity());
        settings.setState(request.getState());
        settings.setPincode(request.getPincode());
        settings.setGstNumber(request.getGstNumber());
        settings.setFssaiNumber(request.getFssaiNumber());
        settings.setPhone(request.getPhone());
        settings.setEmail(request.getEmail());
        settings = companySettingsRepository.save(settings);

        return ApiResponse.<CompanySettingsResponse>builder()
                .success(true)
                .message("Company Settings Updated")
                .data(toResponse(settings))
                .build();
    }

    private CompanySettingsResponse toResponse(CompanySettings s) {
        return CompanySettingsResponse.builder()
                .id(s.getId())
                .companyName(s.getCompanyName())
                .address(s.getAddress())
                .city(s.getCity())
                .state(s.getState())
                .pincode(s.getPincode())
                .gstNumber(s.getGstNumber())
                .fssaiNumber(s.getFssaiNumber())
                .phone(s.getPhone())
                .email(s.getEmail())
                .build();
    }
}
