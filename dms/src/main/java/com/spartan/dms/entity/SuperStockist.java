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
 * Middle layer of the 3-level hierarchy: Company (Admin) -> SuperStockist
 * -> Distributor. Mirrors Distributor's contact/address shape so the two
 * entities stay easy to reason about side by side.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "super_stockists")
public class SuperStockist extends BaseEntity {

    @Column(name = "super_stockist_name", nullable = false, length = 150)
    private String superStockistName;

    @Column(name = "contact_person", nullable = false, length = 100)
    private String contactPerson;

    @Column(name = "mobile_number", nullable = false, unique = true, length = 15)
    private String mobileNumber;

    @Column(name = "email", unique = true, length = 100)
    private String email;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "district", length = 100)
    private String district;

    @Column(name = "pincode", length = 10)
    private String pincode;

    @Column(name = "gst_number", unique = true, length = 20)
    private String gstNumber;

    @Column(name = "license_number", length = 50)
    private String licenseNumber;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}
