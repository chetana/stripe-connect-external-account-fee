package com.djust.stripeconnectdemo.error;

import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case ONBOARDING_REQUIRED -> HttpStatus.CONFLICT;           // 409
            case INSUFFICIENT_FUNDS -> HttpStatus.CONFLICT;            // 409
            case VALIDATION_ERROR, BAD_REQUEST -> HttpStatus.BAD_REQUEST; // 400
            case NOT_FOUND -> HttpStatus.NOT_FOUND;                    // 404
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;         // 429
            case PAYMENT_AUTHENTICATION_FAILED, PAYMENT_DECLINED -> HttpStatus.PAYMENT_REQUIRED; // 402
            case CAPABILITY_NOT_SUPPORTED, CURRENCY_MISMATCH, AMOUNT_TOO_SMALL -> HttpStatus.BAD_REQUEST;
            case STRIPE_API_ERROR -> HttpStatus.BAD_GATEWAY;           // 502 - upstream error
            default -> HttpStatus.INTERNAL_SERVER_ERROR;               // 500
        };
        ApiError body = ApiError.builder()
                .code(ex.getCode().name())
                .message(ex.getMessage())
                .timestamp(Instant.now())
                .details(ex.getDetails())
                .build();
        return new ResponseEntity<>(body, status);
    }

    @ExceptionHandler(StripeException.class)
    public ResponseEntity<ApiError> handleStripe(StripeException ex) {
        // Map Stripe exceptions to stable codes; surface message safely
        ErrorCode code = (ex instanceof RateLimitException) ? ErrorCode.RATE_LIMITED : ErrorCode.STRIPE_API_ERROR;
        HttpStatus status = (ex instanceof RateLimitException) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
        Map<String, Object> details = new HashMap<>();
        if (ex.getCode() != null) details.put("stripe_code", ex.getCode());
        if (ex.getRequestId() != null) details.put("request_id", ex.getRequestId());
        ApiError body = ApiError.builder()
                .code(code.name())
                .message(ex.getMessage())
                .timestamp(Instant.now())
                .details(details.isEmpty() ? null : details)
                .build();
        return new ResponseEntity<>(body, status);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err -> fields.put(err.getField(), err.getDefaultMessage()));
        ApiError body = ApiError.builder()
                .code(ErrorCode.VALIDATION_ERROR.name())
                .message("Validation failed")
                .timestamp(Instant.now())
                .details(fields)
                .build();
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        ApiError body = ApiError.builder()
                .code(ErrorCode.INTERNAL_ERROR.name())
                .message(ex.getMessage())
                .timestamp(Instant.now())
                .build();
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}