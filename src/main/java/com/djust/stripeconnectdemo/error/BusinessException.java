package com.djust.stripeconnectdemo.error;

import lombok.Getter;

import java.util.Map;

/**
 * Simple runtime exception to carry an ErrorCode and optional details.
 */
@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode code;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
        this.details = null;
    }

    public BusinessException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }
}