package com.havita.modules.reporting.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.accounting.domain.PropertyInvoice;
import com.havita.modules.accounting.infrastructure.PropertyInvoiceRepository;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.security.TenantContextHolder;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportingService {

    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;
    private final PropertyInvoiceRepository propertyInvoiceRepository;
    private final TenantRepository tenantRepository;

    /**
     * Genera el Certificado Oficial de Paz y Salvo de Administración en formato PDF
     */
    @Transactional(readOnly = true)
    public byte[] generatePazYSalvoPdf(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        Property property = propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));

        BigDecimal pendingBalance = propertyInvoiceRepository.sumPendingBalanceByProperty(tenantId, propertyId);
        if (pendingBalance != null && pendingBalance.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessRuleConflictException(
                    "PROPERTY_HAS_OUTSTANDING_DEBT",
                    "No se puede expedir Paz y Salvo. El inmueble " + property.getNumber() + " presenta saldo pendiente de $" + pendingBalance
            );
        }

        // Solo relaciones vigentes: un propietario dado de baja sigue en el historial pero no figura en el certificado.
        List<Resident> residents = residentRepository.findByTenantIdAndPropertyId(tenantId, propertyId);
        String residentName = residents.stream()
                .filter(r -> r.getStatus() == com.havita.modules.resident.domain.ResidentStatus.ACTIVE)
                .filter(r -> Boolean.TRUE.equals(r.getIsMainContact()) || "Propietario".equalsIgnoreCase(r.getType()))
                .map(Resident::getName)
                .findFirst()
                .orElse("PROPIETARIO REGISTRADO");

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();

            // Tipografías
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, BaseColor.DARK_GRAY);
            Font subTitleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, BaseColor.GRAY);
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 11, BaseColor.BLACK);
            Font boldFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, BaseColor.BLACK);

            // Encabezado
            Paragraph title = new Paragraph(tenant.getName().toUpperCase(), titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);

            Paragraph nit = new Paragraph("NIT: " + (tenant.getNit() != null ? tenant.getNit() : "N/A"), subTitleFont);
            nit.setAlignment(Element.ALIGN_CENTER);
            document.add(nit);

            Paragraph docTitle = new Paragraph("\nCERTIFICADO DE PAZ Y SALVO DE ADMINISTRACIÓN\n\n", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, new BaseColor(30, 64, 175)));
            docTitle.setAlignment(Element.ALIGN_CENTER);
            document.add(docTitle);

            // Cuerpo del Paz y Salvo
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy"));
            Paragraph body = new Paragraph(
                    "La Administración del Conjunto Residencial " + tenant.getName() + " CERTIFICA QUE:\n\n" +
                    "El inmueble identificado con la unidad " + property.getNumber() + " (" + property.getName() + "),\n" +
                    "a nombre de " + residentName + ",\n\n" +
                    "Se encuentra a la fecha (" + today + ") a PAZ Y SALVO por todo concepto relacionado " +
                    "con cuotas de administración ordinarias, extraordinarias, recargos por mora e intereses.\n\n" +
                    "Este certificado se expide a solicitud del interesado con destino a trámites notariales, venta o requerimientos pertinentes.\n\n\n",
                    bodyFont
            );
            body.setAlignment(Element.ALIGN_JUSTIFIED);
            document.add(body);

            // Firma de la Administración
            Paragraph signature = new Paragraph(
                    "_________________________________________\n" +
                    "ADMINISTRACIÓN Y REPRESENTACIÓN LEGAL\n" +
                    tenant.getName() + "\n" +
                    "Generado a través de Havita SaaS",
                    boldFont
            );
            signature.setAlignment(Element.ALIGN_CENTER);
            document.add(signature);

            document.close();
            log.info("Paz y Salvo generated for property {}", property.getNumber());
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Error generating Paz y Salvo PDF", e);
            throw new RuntimeException("Error al generar el certificado PDF de Paz y Salvo", e);
        }
    }

    /**
     * Exporta el Balance de Cartera de todos los inmuebles de la copropiedad a formato Excel (.xlsx)
     */
    @Transactional(readOnly = true)
    public byte[] exportPortfolioReportExcel() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));

        List<Property> properties = propertyRepository.findByTenantId(tenantId);

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Estado de Cartera");

            // Estilos de encabezado
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            CellStyle headerCellStyle = workbook.createCellStyle();
            headerCellStyle.setFont(headerFont);
            headerCellStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerCellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Fila de encabezados
            Row headerRow = sheet.createRow(0);
            String[] columns = {"Inmueble", "Tipo", "Ubicación", "Coeficiente", "Estado Inmueble", "Saldo Pendiente"};
            for (int i = 0; i < columns.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerCellStyle);
            }

            // Filas de datos
            int rowIdx = 1;
            BigDecimal totalCartera = BigDecimal.ZERO;

            for (Property property : properties) {
                Row row = sheet.createRow(rowIdx++);
                BigDecimal balance = propertyInvoiceRepository.sumPendingBalanceByProperty(tenantId, property.getId());
                if (balance == null) balance = BigDecimal.ZERO;
                totalCartera = totalCartera.add(balance);

                row.createCell(0).setCellValue(property.getNumber());
                row.createCell(1).setCellValue(property.getType());
                row.createCell(2).setCellValue(property.getName() != null ? property.getName() : "N/A");
                row.createCell(3).setCellValue(property.getCoefficient() != null ? property.getCoefficient().doubleValue() : 0.0);
                row.createCell(4).setCellValue(property.getStatus().name());
                row.createCell(5).setCellValue(balance.doubleValue());
            }

            // Fila de total
            Row totalRow = sheet.createRow(rowIdx);
            Cell totalLabel = totalRow.createCell(4);
            totalLabel.setCellValue("TOTAL CARTERA:");
            Cell totalVal = totalRow.createCell(5);
            totalVal.setCellValue(totalCartera.doubleValue());

            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            log.info("Portfolio Excel report generated for tenant {}", tenantId);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Error generating portfolio Excel report", e);
            throw new RuntimeException("Error al exportar reporte de cartera en Excel", e);
        }
    }

    /**
     * Exporta el Censo General de Residentes en formato CSV delimitado por comas
     */
    @Transactional(readOnly = true)
    public byte[] exportResidentsCensusCsv() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        List<Resident> residents = residentRepository.findByTenantId(tenantId);

        StringBuilder sb = new StringBuilder();
        sb.append("Inmueble,Nombre,Tipo Documento,Numero Documento,Tipo Residente,Telefono,Email,Contacto Principal,Estado\n");

        for (Resident r : residents) {
            sb.append("\"").append(r.getProperty().getNumber()).append("\",");
            sb.append("\"").append(r.getName().replace("\"", "\"\"")).append("\",");
            sb.append("\"").append(r.getDocumentType() != null ? r.getDocumentType() : "").append("\",");
            sb.append("\"").append(r.getDocumentId() != null ? r.getDocumentId() : "").append("\",");
            sb.append("\"").append(r.getType()).append("\",");
            sb.append("\"").append(r.getPhone() != null ? r.getPhone() : "").append("\",");
            sb.append("\"").append(r.getEmail() != null ? r.getEmail() : "").append("\",");
            sb.append(Boolean.TRUE.equals(r.getIsMainContact()) ? "SI" : "NO").append(",");
            sb.append("\"").append(r.getStatus()).append("\"\n");
        }

        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
