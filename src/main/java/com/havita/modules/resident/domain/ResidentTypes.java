package com.havita.modules.resident.domain;

import com.havita.common.exception.InvalidRequestException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fase 2 / P0-C: valores validos de {@code residents.type}. La columna es texto libre (sin CHECK) y los indices unicos
 * parciales de PostgreSQL comparan la grafia exacta ({@code 'Arrendatario'}, {@code 'Residente'}), mientras que el
 * codigo compara sin distinguir mayusculas. Por eso toda entrada se valida y se guarda con la grafia canonica: un
 * valor desconocido se rechaza (400) y nunca se convierte en otro tipo.
 *
 * Solo se normalizan mayusculas y espacios de un valor que ya es uno de los tres tipos; no hay sinonimos ni traducciones
 * (OWNER, TENANT, RESIDENT o "inquilino" se rechazan).
 */
public final class ResidentTypes {

    public static final String OWNER = "Propietario";
    public static final String TENANT = "Arrendatario";
    public static final String RESIDENT = "Residente";

    public static final List<String> ALL = List.of(OWNER, TENANT, RESIDENT);
    /** Tipos que un residente puede invitar a su unidad (los propietarios los crea la administracion o un traspaso). */
    public static final Set<String> HOUSEHOLD_INVITABLE = Set.of(TENANT, RESIDENT);

    public static final String INVALID_CODE = "INVALID_RESIDENT_TYPE";

    private ResidentTypes() {}

    /**
     * Grafia canonica de {@code raw}. {@code null} devuelve {@code null} (el llamador decide el valor por defecto).
     * Un valor vacio o desconocido lanza {@link InvalidRequestException} con {@link #INVALID_CODE}.
     */
    public static String canonicalOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (String type : ALL) {
            if (type.toLowerCase(Locale.ROOT).equals(key)) {
                return type;
            }
        }
        throw new InvalidRequestException(INVALID_CODE,
                "Tipo de residente no valido. Valores permitidos: Propietario, Arrendatario o Residente.");
    }

    /** Como {@link #canonicalOrNull(String)}, con {@code defaultType} cuando el valor no se envia ({@code null}). */
    public static String canonicalOrDefault(String raw, String defaultType) {
        String canonical = canonicalOrNull(raw);
        return canonical != null ? canonical : defaultType;
    }
}
