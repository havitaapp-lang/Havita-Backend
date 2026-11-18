package com.havita.common.exception;

import org.springframework.http.HttpStatus;

/** Solicitud mal formada desde el punto de vista del dominio (400): faltan datos o se contradicen. */
public class InvalidRequestException extends DomainException {
    public InvalidRequestException(String code, String message) {
        super(code, message, HttpStatus.BAD_REQUEST);
    }
}
