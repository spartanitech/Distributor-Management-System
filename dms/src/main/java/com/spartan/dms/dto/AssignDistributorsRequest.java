package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body for POST /api/v1/super-stockists/{id}/assign-distributors.
 * distributorIds is the complete desired set of distributors assigned to
 * that Super Stockist — any currently-assigned distributor not in this
 * list is unassigned (super_stockist set to null), and every id in the
 * list gets super_stockist set to {id}. Sending the full set rather than
 * a delta keeps this idempotent and avoids needing separate add/remove
 * endpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignDistributorsRequest {

    private List<Long> distributorIds;
}
