package com.djust.stripeconnectdemo.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory store for demo purposes.
 * - accounts: accountId -> true (for listing). Status is fetched live from Stripe.
 */
@Component
public class MemoryStore {
    private final Map<String, Boolean> accounts = new LinkedHashMap<>();

    public Map<String, Boolean> getAccounts() { return accounts; }
}