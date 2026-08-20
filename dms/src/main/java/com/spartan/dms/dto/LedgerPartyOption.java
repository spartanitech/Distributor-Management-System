package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerPartyOption {

    private Long id;
    private String name;
    private String partyType; // SHOP or DISTRIBUTOR
    private String subtitle;  // district/city, shown under the name in the picker
}
