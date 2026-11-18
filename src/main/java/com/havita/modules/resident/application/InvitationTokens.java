package com.havita.modules.resident.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * SEC-7: tokens de invitacion. El token es un secreto aleatorio de 256 bits (32 bytes de {@link SecureRandom} en
 * base64url, 43 caracteres) que solo se entrega al emitir o regenerar la invitacion. En base de datos se guarda
 * unicamente su SHA-256 en hexadecimal (64 caracteres). Con esa entropia no hace falta un hash lento: no hay
 * diccionario posible, y la busqueda por hash sigue usando el indice unico de {@code token_hash}.
 */
public final class InvitationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern HASHED = Pattern.compile("^[0-9a-f]{64}$");

    private InvitationTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** {@code true} si el valor guardado ya es un hash; los tokens heredados en claro miden 43 caracteres. */
    public static boolean isHashed(String stored) {
        return stored != null && HASHED.matcher(stored).matches();
    }
}
