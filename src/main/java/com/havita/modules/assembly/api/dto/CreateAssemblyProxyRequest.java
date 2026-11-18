package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Fase 2 / P1: solicitud de poder para una asamblea. El documento de soporte va en la parte {@code document} de la
 * peticion multipart. {@code grantorResidentId} solo lo usa la administracion al registrar en nombre del propietario;
 * en la solicitud del propietario el otorgante es siempre el llamante.
 */
public record CreateAssemblyProxyRequest(
        @NotNull(message = "La unidad es obligatoria")
        UUID propertyId,

        UUID grantorResidentId,

        UUID proxyUserId,

        @NotBlank(message = "El nombre del apoderado es obligatorio")
        @Size(max = 150, message = "El nombre del apoderado no puede exceder 150 caracteres")
        String proxyFullName,

        @NotBlank(message = "El tipo de documento del apoderado es obligatorio")
        @Pattern(regexp = "CC|CE|PASAPORTE|PEP", message = "Tipo de documento no valido (CC, CE, PASAPORTE o PEP)")
        String proxyDocumentType,

        @NotBlank(message = "El numero de documento del apoderado es obligatorio")
        @Size(max = 50, message = "El numero de documento no puede exceder 50 caracteres")
        @Pattern(regexp = "[A-Za-z0-9-]+", message = "El numero de documento solo admite letras, numeros y guiones")
        String proxyDocumentNumber,

        /*
         * Fase 2 / P7 (G3): SOLO en el registro por la administracion. Describe como recibio la administracion el poder
         * firmado por el otorgante (el archivo adjunto es esa evidencia). Que tipos son juridicamente admisibles es una
         * decision pendiente (P5_LEGAL_REVIEW_CHECKLIST §1): el sistema solo lo registra y no lo convierte en aprobacion.
         */
        @Pattern(regexp = "SIGNED_DOCUMENT_IN_PERSON|SIGNED_DOCUMENT_SENT_BY_GRANTOR|AUTHENTICATED_DOCUMENT",
                message = "Tipo de evidencia no valido")
        String evidenceType,

        /** P7 (G3): la administracion declara que el documento adjunto es el poder firmado por el otorgante. */
        Boolean grantorConsentAttested
) {
    /** Constructor de compatibilidad (solicitud del propietario y pruebas anteriores a P7): sin evidencia administrativa. */
    public CreateAssemblyProxyRequest(UUID propertyId, UUID grantorResidentId, UUID proxyUserId, String proxyFullName,
                                      String proxyDocumentType, String proxyDocumentNumber) {
        this(propertyId, grantorResidentId, proxyUserId, proxyFullName, proxyDocumentType, proxyDocumentNumber, null, null);
    }
}
