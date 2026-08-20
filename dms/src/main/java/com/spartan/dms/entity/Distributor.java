package com.spartan.dms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "distributors")
public class Distributor extends BaseEntity {

    // Every distributor belongs to exactly one Super Stockist, but that
    // assignment is deliberately NOT set at creation time (DistributorRequest
    // has no superStockistId field) -- a distributor is created unassigned
    // and then linked via SuperStockistService.assignDistributors(), which
    // supports bulk (re)assignment. Nullable at the DB level to allow both
    // that intentional "created, not yet assigned" state and safe migration
    // of any pre-existing rows seeded before this hierarchy existed.
    // DistributorService.getUnassignedDistributors() lists any distributor
    // still in this state so it doesn't go unnoticed.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "super_stockist_id")
    private SuperStockist superStockist;

    @Column(name = "distributor_name", nullable = false, length = 150)
    private String distributorName;

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