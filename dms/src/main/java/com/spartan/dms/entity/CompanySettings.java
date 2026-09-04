package com.spartan.dms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Singleton row (always id=1) holding the company's own letterhead details
 * — printed as the "seller" block on COMPANY_TO_SUPER_STOCKIST invoices.
 * Admin can edit these from Settings; every other read is public within
 * the app (needed to render invoices) but writes are admin-only
 * (enforced in CompanySettingsService, not here).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "company_settings")
public class CompanySettings extends BaseEntity {

    @Column(name = "company_name", length = 150)
    private String companyName;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "pincode", length = 10)
    private String pincode;

    @Column(name = "gst_number", length = 20)
    private String gstNumber;

    @Column(name = "fssai_number", length = 30)
    private String fssaiNumber;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 100)
    private String email;
}
