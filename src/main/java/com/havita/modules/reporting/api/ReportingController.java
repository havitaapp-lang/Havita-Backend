package com.havita.modules.reporting.api;

import com.havita.modules.reporting.application.ReportingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reporting")
@RequiredArgsConstructor
@Tag(name = "Export & Reporting", description = "Generación y descarga de Paz y Salvos (PDF), cartera (Excel) y censos (CSV)")
public class ReportingController {

    private final ReportingService reportingService;

    @GetMapping("/properties/{propertyId}/paz-y-salvo")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'ACCOUNTANT') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Descargar Certificado de Paz y Salvo en PDF", description = "Verifica que el inmueble no posea deuda y expide el documento formal")
    public ResponseEntity<byte[]> downloadPazYSalvo(@PathVariable UUID propertyId) {
        byte[] pdfBytes = reportingService.generatePazYSalvoPdf(propertyId);
        String filename = "paz-y-salvo-" + propertyId + "-" + LocalDate.now() + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(pdfBytes);
    }

    @GetMapping("/portfolio/excel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'ACCOUNTANT')")
    @Operation(summary = "Exportar Estado General de Cartera en Excel (.xlsx)")
    public ResponseEntity<byte[]> exportPortfolioExcel() {
        byte[] excelBytes = reportingService.exportPortfolioReportExcel();
        String filename = "cartera-general-" + LocalDate.now() + ".xlsx";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(excelBytes);
    }

    @GetMapping("/residents/census/csv")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Exportar Censo Poblacional de Residentes en formato CSV")
    public ResponseEntity<byte[]> exportResidentsCensusCsv() {
        byte[] csvBytes = reportingService.exportResidentsCensusCsv();
        String filename = "censo-residentes-" + LocalDate.now() + ".csv";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csvBytes);
    }
}
