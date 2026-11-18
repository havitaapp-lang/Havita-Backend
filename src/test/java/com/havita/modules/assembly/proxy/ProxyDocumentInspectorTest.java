package com.havita.modules.assembly.proxy;

import com.havita.common.exception.InvalidRequestException;
import com.havita.modules.assembly.application.ProxyDocumentInspector;
import com.havita.modules.assembly.application.ProxyDocumentInspector.DetectedType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fase 2 / P1: el tipo del documento se decide por su contenido, no por nombre ni Content-Type. */
class ProxyDocumentInspectorTest {

    static final byte[] PDF = "%PDF-1.7\n%synthetic\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};

    @Test
    @DisplayName("PDF, PNG y JPEG reconocidos por su firma")
    void detected() {
        assertThat(ProxyDocumentInspector.inspect(PDF, 1024)).isEqualTo(DetectedType.PDF);
        assertThat(ProxyDocumentInspector.inspect(PNG, 1024)).isEqualTo(DetectedType.PNG);
        assertThat(ProxyDocumentInspector.inspect(JPEG, 1024)).isEqualTo(DetectedType.JPEG);
        assertThat(ProxyDocumentInspector.sha256(PDF)).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("vacio, demasiado grande o de otro tipo (texto, HTML, ejecutable, SVG): rechazado")
    void rejected() {
        assertCode(null, "PROXY_DOCUMENT_REQUIRED");
        assertCode(new byte[0], "PROXY_DOCUMENT_REQUIRED");
        assertCode(new byte[2048], "PROXY_DOCUMENT_TOO_LARGE");
        for (String other : new String[]{"hola, soy un poder", "<html><script>x</script>", "MZ\u0090\u0000", "<svg xmlns=\"x\"/>", " %PDF-1.7"}) {
            assertCode(other.getBytes(StandardCharsets.ISO_8859_1), "INVALID_PROXY_DOCUMENT");
        }
    }

    private static void assertCode(byte[] content, String code) {
        assertThatThrownBy(() -> ProxyDocumentInspector.inspect(content, 1024))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
