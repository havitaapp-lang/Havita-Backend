package com.havita.modules.assembly.application;

import com.havita.common.exception.InvalidRequestException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fase 2 / P1: valida el documento de un poder POR SU CONTENIDO (firma de los primeros bytes), no por el nombre ni por el
 * Content-Type que envia el cliente. Solo PDF, PNG y JPEG. El tamano maximo es configurable.
 */
public final class ProxyDocumentInspector {

    public enum DetectedType {
        PDF("application/pdf", "pdf"), PNG("image/png", "png"), JPEG("image/jpeg", "jpg");

        public final String contentType;
        public final String extension;

        DetectedType(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }

    private ProxyDocumentInspector() {}

    public static DetectedType inspect(byte[] content, long maxBytes) {
        if (content == null || content.length == 0) {
            throw new InvalidRequestException("PROXY_DOCUMENT_REQUIRED", "El poder debe incluir un documento de soporte.");
        }
        if (content.length > maxBytes) {
            throw new InvalidRequestException("PROXY_DOCUMENT_TOO_LARGE", "El documento supera el tamaño máximo permitido.");
        }
        if (startsWith(content, new byte[]{'%', 'P', 'D', 'F', '-'})) {
            return DetectedType.PDF;
        }
        if (startsWith(content, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return DetectedType.PNG;
        }
        if (startsWith(content, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return DetectedType.JPEG;
        }
        throw new InvalidRequestException("INVALID_PROXY_DOCUMENT", "El documento debe ser un PDF, PNG o JPEG válido.");
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) return false;
        }
        return true;
    }
}
