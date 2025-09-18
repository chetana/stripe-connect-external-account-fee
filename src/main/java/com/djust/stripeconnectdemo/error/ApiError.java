package com.djust.stripeconnectdemo.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiError {
    private String code;          // stable error code (matches ErrorCode)
    private String message;       // human-readable message (i18n by frontend if desired)
    private Instant timestamp;    // server time

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> details; // optional extra data (e.g., onboarding_url, available_by_currency)
}