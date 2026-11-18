package com.havita.common.exception;

import org.springframework.http.HttpStatus;

public class BusinessRuleConflictException extends DomainException {
    public BusinessRuleConflictException(String code, String message) {
        super(code, message, HttpStatus.CONFLICT);
    }
}
