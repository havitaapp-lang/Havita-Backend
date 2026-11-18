package com.havita.modules.assembly.application;

import com.havita.security.AccessValidator;
import com.havita.security.TenantContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fase 2 / P5-C — conciliacion SOLO DE LECTURA entre PostgreSQL y el almacenamiento de documentos de poderes.
 *
 * PostgreSQL y R2 no comparten transaccion. El protocolo es: subir el objeto (clave nueva, aleatoria, con el id de la
 * operacion) → insertar o actualizar la fila → si la transaccion no confirma, borrar el objeto (compensacion). Quedan
 * dos fallos parciales posibles, que este informe detecta:
 * <ul>
 *   <li><b>huerfano</b>: objeto sin fila que lo referencie (la compensacion fallo, el proceso se interrumpio entre la
 *       subida y el commit, o un documento en revision fue reemplazado);</li>
 *   <li><b>ausente</b>: fila que referencia un objeto que no existe (borrado externo o perdida).</li>
 * </ul>
 * NO borra nada: un documento de un poder aprobado o utilizado es evidencia y no se elimina sin una politica de
 * conservacion autorizada. Los huerfanos se informan con el id de la operacion (si se reconoce) para revision manual.
 * Nunca devuelve claves de almacenamiento.
 */
@Component
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "true")
public class ProxyDocumentReconciler {

    private static final Pattern OPERATION = Pattern.compile(
            "poder-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.[a-z]+$");

    public record Report(int storedObjects, int referencedDocuments, int orphanObjects, List<UUID> orphanOperationIds,
                         int unrecognizedOrphans, List<UUID> proxiesWithMissingDocument) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ProxyDocumentStorage documents;
    private final AccessValidator accessValidator;

    public ProxyDocumentReconciler(NamedParameterJdbcTemplate jdbc, ProxyDocumentStorage documents, AccessValidator accessValidator) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.accessValidator = accessValidator;
    }

    /** Informe de la comunidad actual (administracion vigente). */
    @Transactional(readOnly = true)
    public Report reportForCurrentCommunity() {
        accessValidator.requireCommunityAdministrator();
        return report(TenantContextHolder.getRequiredTenantId());
    }

    @Transactional(readOnly = true)
    public Report report(UUID tenantId) {
        List<String[]> rows = jdbc.query("SELECT CAST(id AS varchar), document_key FROM assembly_proxies WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenantId), (rs, i) -> new String[]{rs.getString(1), rs.getString(2)});
        Set<String> referenced = new HashSet<>();
        List<UUID> missing = new java.util.ArrayList<>();
        for (String[] r : rows) {
            referenced.add(r[1]);
            if (!documents.exists(r[1])) {
                missing.add(UUID.fromString(r[0]));
            }
        }
        List<String> stored = documents.listKeys(tenantId);
        List<UUID> orphanOps = new java.util.ArrayList<>();
        int unrecognized = 0;
        int orphans = 0;
        for (String key : stored) {
            if (referenced.contains(key)) {
                continue;
            }
            orphans++;
            Matcher m = OPERATION.matcher(key);
            if (m.find()) {
                orphanOps.add(UUID.fromString(m.group(1)));
            } else {
                unrecognized++;
            }
        }
        return new Report(stored.size(), referenced.size(), orphans, orphanOps, unrecognized, missing);
    }
}
