package com.spartan.dms.controller;

import com.spartan.dms.dto.AccountLedgerResponse;
import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.dto.LedgerPartyOption;
import com.spartan.dms.service.AccountLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/account-ledger")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
public class AccountLedgerController {

    private final AccountLedgerService accountLedgerService;

    @GetMapping("/parties")
    public ResponseEntity<ApiResponse<List<LedgerPartyOption>>> getParties(
            @RequestParam(required = false) String partyType) {
        return ResponseEntity.ok(accountLedgerService.getParties(partyType));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AccountLedgerResponse>> getLedger(
            @RequestParam String partyType,
            @RequestParam Long partyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        return ResponseEntity.ok(accountLedgerService.getLedger(partyType, partyId, fromDate, toDate));
    }

    @GetMapping(value = "/export/csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam String partyType, @RequestParam Long partyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        byte[] csv = accountLedgerService.exportCsv(partyType, partyId, fromDate, toDate);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=ledger.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @GetMapping(value = "/export/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam String partyType, @RequestParam Long partyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) throws IOException {

        byte[] excel = accountLedgerService.exportExcel(partyType, partyId, fromDate, toDate);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=ledger.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel);
    }

    @GetMapping(value = "/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(
            @RequestParam String partyType, @RequestParam Long partyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        byte[] pdf = accountLedgerService.exportPdf(partyType, partyId, fromDate, toDate);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=ledger.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
