package com.havita.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends DomainException {
    public ResourceNotFoundException(String resource, Object id) {
        super(resource.toUpperCase() + "_NOT_FOUND",
              String.format("El recurso %s con identificador %s no fue encontrado.", resource, id),
              HttpStatus.NOT_FOUND);
    }
}
