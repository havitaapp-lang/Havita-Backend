package com.havita.common.exception;

import com.havita.common.api.CorrelationIdFilter;
import com.havita.common.api.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex, HttpServletRequest request) {
        // SEC-9 (P2): el mensaje va al cliente, pero no al log: puede incluir correos, documentos o nombres. El codigo,
        // la ruta (enmascarada) y el traceId bastan para la trazabilidad; el detalle queda en DEBUG.
        log.warn("Excepción de dominio interceptada [{}] en {}", ex.getCode(), safePath(request));
        log.debug("Detalle de la excepción de dominio [{}]: {}", ex.getCode(), ex.getMessage());
        return buildResponse(
                ex.getStatus(),
                ex.getCode(),
                "Error de Regla de Negocio",
                ex.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorResponse.ValidationErrorItem> errorItems = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errorItems.add(new ErrorResponse.ValidationErrorItem(
                    fieldError.getField(),
                    fieldError.getDefaultMessage(),
                    fieldError.getRejectedValue()
            ));
        }

        log.warn("Fallo de validación en request {}: {} errores de campos", safePath(request), errorItems.size());
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Error de Validación de Datos",
                "Uno o más campos no cumplen con las restricciones requeridas.",
                request,
                errorItems
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Acceso denegado en {}: {}", safePath(request), ex.getMessage());
        String message = (ex.getMessage() != null && !ex.getMessage().isBlank() && !"Access Denied".equalsIgnoreCase(ex.getMessage()))
                ? ex.getMessage()
                : "No cuenta con los privilegios o el contexto de rol necesario para esta operación.";
        return buildResponse(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "Acceso Denegado",
                message,
                request,
                null
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        log.warn("Fallo de autenticación en {}: {}", safePath(request), ex.getMessage());
        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "No Autenticado",
                "Credenciales ausentes, inválidas o expiradas.",
                request,
                null
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        log.debug("Ruta no encontrada: {}", safePath(request));
        return buildResponse(
                HttpStatus.NOT_FOUND,
                "ENDPOINT_NOT_FOUND",
                "Recurso No Encontrado",
                "La ruta solicitada no existe.",
                request,
                null
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        log.debug("Método no permitido: {}", safePath(request));
        ResponseEntity<ErrorResponse> response = buildResponse(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "Método No Permitido",
                "El método HTTP utilizado no está permitido para esta ruta.",
                request,
                null
        );
        return ResponseEntity.status(response.getStatusCode())
                .headers(ex.getHeaders())
                .body(response.getBody());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        log.debug("Tipo de contenido no soportado: {}", safePath(request));
        return buildResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Tipo de Contenido No Soportado",
                "El tipo de contenido de la solicitud no está soportado.",
                request,
                null
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Cuerpo de solicitud no legible: {}", safePath(request));
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST_BODY",
                "Solicitud Mal Formada",
                "El cuerpo de la solicitud no es válido o no se pudo leer.",
                request,
                null
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        log.debug("Parámetro requerido ausente: {}", safePath(request));
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "MISSING_REQUEST_PARAMETER",
                "Parámetro Requerido Ausente",
                "Falta un parámetro obligatorio en la solicitud.",
                request,
                null
        );
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestPart(MissingServletRequestPartException ex, HttpServletRequest request) {
        log.debug("Parte requerida ausente: {}", safePath(request));
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "MISSING_REQUEST_PARAMETER",
                "Parámetro Requerido Ausente",
                "Falta un parámetro obligatorio en la solicitud.",
                request,
                null
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.debug("Tipo de parámetro inválido: {}", safePath(request));
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER_TYPE",
                "Parámetro con Formato Inválido",
                "Uno o más parámetros de la solicitud tienen un formato inválido.",
                request,
                null
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.debug("Tamaño de archivo excedido: {}", safePath(request));
        return buildResponse(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "PAYLOAD_TOO_LARGE",
                "Archivo Demasiado Grande",
                "El archivo o la solicitud supera el tamaño máximo permitido.",
                request,
                null
        );
    }

    /**
     * SEC-9 (P1): la comprobacion transaccional de permisos coincidio con un cambio concurrente (espera agotada o
     * interbloqueo detectado por PostgreSQL). No se realizo nada; el cliente puede reintentar.
     */
    @ExceptionHandler({org.springframework.dao.PessimisticLockingFailureException.class,
            jakarta.persistence.PessimisticLockException.class, jakarta.persistence.LockTimeoutException.class})
    public ResponseEntity<ErrorResponse> handleConcurrentAccessChange(Exception ex, HttpServletRequest request) {
        log.warn("Operacion rechazada por cambio concurrente en {}: {}", safePath(request), ex.getClass().getSimpleName());
        return buildResponse(HttpStatus.CONFLICT, "CONCURRENT_ACCESS_CHANGE", "Conflicto de concurrencia",
                "La operación coincidió con un cambio de permisos. Inténtalo de nuevo.", request, null);
    }

    /**
     * Fase 2 / P0-C: una violacion de un indice unico de residencia conocido es un conflicto de negocio (409) con el
     * mismo codigo que usan las comprobaciones de los servicios. Cualquier otra violacion de integridad (otra unicidad,
     * clave foranea, CHECK, NOT NULL) no es un conflicto conocido y recibe el tratamiento de error inesperado.
     * La excepcion puede surgir en el flush o en el commit; aqui ya no hay sesion JPA que reutilizar.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest request) {
        var conflict = UniqueConstraintConflicts.classify(ex);
        if (conflict.isEmpty()) {
            return handleUnexpectedException(ex, request);
        }
        log.warn("Conflicto de integridad [{}] en {}", conflict.get().code(), safePath(request));
        return buildResponse(HttpStatus.CONFLICT, conflict.get().code(), "Error de Regla de Negocio",
                conflict.get().message(), request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex, HttpServletRequest request) {
        // Ignorar errores de conexión interrumpida por el cliente (común en SSE o cancelaciones de request)
        if (ex.getClass().getName().contains("ClientAbortException") || ex instanceof java.io.IOException) {
            log.debug("Conexión interrumpida por el cliente al procesar {}: {}", safePath(request), ex.getMessage());
            return null; // Spring manejará la desconexión
        }

        log.error("Error inesperado en servidor al procesar " + safePath(request), ex);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_SERVER_ERROR",
                "Error Interno del Servidor",
                "Ha ocurrido un error inesperado al procesar la solicitud. Nuestro equipo técnico ha sido notificado.",
                request,
                null
        );
    }

    private ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status,
            String code,
            String title,
            String message,
            HttpServletRequest request,
            List<ErrorResponse.ValidationErrorItem> errors
    ) {
        String traceId = MDC.get(CorrelationIdFilter.MDC_TRACE_ID_KEY);
        ErrorResponse response = new ErrorResponse(
                "https://api.havita.com/errors/" + code.toLowerCase(),
                title,
                status.value(),
                code,
                message,
                safePath(request),
                OffsetDateTime.now(),
                traceId != null ? traceId : "N/A",
                errors != null ? errors : List.of()
        );
        return ResponseEntity.status(status).body(response);
    }

    /**
     * SEC-7: ruta apta para logs y respuestas de error. Los enlaces de invitacion llevan el token en la ruta
     * ({@code /public/resident-invitations/{token}}); se enmascara para no escribir ni devolver el secreto.
     */
    static String safePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null ? null : INVITATION_TOKEN_IN_PATH.matcher(uri).replaceAll("$1***");
    }

    private static final java.util.regex.Pattern INVITATION_TOKEN_IN_PATH =
            java.util.regex.Pattern.compile("(/public/resident-invitations/)[^/]+");
}
