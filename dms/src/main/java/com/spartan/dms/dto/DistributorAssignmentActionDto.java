package com.spartan.dms.dto;

import com.spartan.dms.enums.AssignmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributorAssignmentActionDto {

    // APPROVED, MODIFIED, or REJECTED — a distributor's response to a
    // pending assignment. Never PENDING (that's the initial state only).
    private AssignmentStatus status;

    // Required (and only meaningful) when status == MODIFIED.
    private Integer modifiedQuantity;

    private String distributorRemarks;
}
