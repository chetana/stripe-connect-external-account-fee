package com.djust.stripeconnectdemo.config;

import com.stripe.StripeClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@ConfigurationProperties(prefix = "stripe")
public class StripeConfig {

    /** Secret key, ex: sk_test_... Must be provided. */
    private String secretKey;
    /** Target API version to lock behavior to. */
    private String apiVersion;
    /** Public root URL used for redirects (return/refresh/success). */
    private String rootUrl;
    /** Publishable key used by Stripe.js on the client (optional). */
    private String publishableKey;

    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    public void setRootUrl(String rootUrl) { this.rootUrl = rootUrl; }
    public void setPublishableKey(String publishableKey) { this.publishableKey = publishableKey; }

    public String getRootUrl() { return rootUrl; }
    public String getPublishableKey() { return publishableKey; }

    @Bean
    public StripeClient stripeClient() {
        // Fail fast with clear messages if configuration is missing
        if (!StringUtils.hasText(secretKey) || secretKey.contains("__PUT_YOUR_")) {
            throw new IllegalStateException("Missing Stripe secret key (stripe.secretKey). Hint: set in application.yaml or env STRIPE_SECRETKEY");
        }

        // Build Stripe client; version defaults to account settings
        return new StripeClient(secretKey);
    }
}