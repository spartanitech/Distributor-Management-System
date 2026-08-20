package com.spartan.dms.entity;

import jakarta.persistence.*;
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
@Table(name = "shops")
public class Shop extends BaseEntity {

    @Column(name = "shop_name", nullable = false, length = 150)
    private String shopName;

    @Column(name = "owner_name", nullable = false, length = 100)
    private String ownerName;

    @Column(name = "mobile_number", nullable = false, unique = true, length = 15)
    private String mobileNumber;

    @Column(name = "email", length = 100)
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

    @Column(name = "gst_number", length = 20)
    private String gstNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Distributor distributor;

    @Column(name = "remarks", length = 500)
    private String remarks;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}