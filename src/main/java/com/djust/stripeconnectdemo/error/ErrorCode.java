package com.djust.stripeconnectdemo.error;

/**
 * Centralized error codes returned by the API.
 * These codes are stable and can be relied on by the frontend.
 */
public enum ErrorCode {
    // Onboarding / capabilities
    ONBOARDING_REQUIRED,
    CAPABILITY_NOT_SUPPORTED,

    // Payments
    PAYMENT_AUTHENTICATION_FAILED,
    PAYMENT_DECLINED,

    // Transfers / balance
    INSUFFICIENT_FUNDS,
    CURRENCY_MISMATCH,
    AMOUNT_TOO_SMALL,

    // Generic validations / conflicts
    VALIDATION_ERROR,
    BAD_REQUEST,
    CONFLICT,
    NOT_FOUND,

    // Stripe and infrastructure
    STRIPE_API_ERROR,
    RATE_LIMITED,
    INTERNAL_ERROR
}