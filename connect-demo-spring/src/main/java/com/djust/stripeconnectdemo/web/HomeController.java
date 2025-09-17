package com.djust.stripeconnectdemo.web;

import com.djust.stripeconnectdemo.config.StripeConfig;
import com.djust.stripeconnectdemo.service.MemoryStore;
import com.stripe.StripeClient;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.PaymentIntent;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MVC Controller providing:
 * - / : manage accounts (HTML)
 * - /api/state : live state for accounts (JSON)
 * - POST /accounts : create connected account (controller-only)
 * - POST /accounts/{id}/onboard : create Account Link
 * - POST /payments : create PaymentIntent (OMS-driven flow) with destination + application fee
 */
@Controller
@RequiredArgsConstructor
@Validated
public class HomeController {

    private final StripeClient stripe;
    private final StripeConfig config;
    private final MemoryStore store;

    @GetMapping("/")
    public String home(Model model) {
        // The HTML fetches JSON from /api/state; this page only loads the UI shell.
        return "index";
    }

    @GetMapping("/pay")
    public String pay(
            @RequestParam(value = "amount", required = false) Long amount,
            @RequestParam(value = "currency", required = false) String currency,
            @RequestParam(value = "connected_account_id", required = false) String connectedAccountId,
            @RequestParam(value = "application_fee_amount", required = false) Long applicationFeeAmount,
            @RequestParam(value = "order_id", required = false) String orderId,
            Model model) throws Exception {
        model.addAttribute("publishableKey", config.getPublishableKey());
        model.addAttribute("amount", amount);
        model.addAttribute("currency", currency);
        model.addAttribute("connected_account_id", connectedAccountId);
        model.addAttribute("application_fee_amount", applicationFeeAmount);
        model.addAttribute("order_id", orderId);
        return "pay";
    }

    @GetMapping(value = "/api/state", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> state() throws Exception {
        // Fetch live account status from Stripe for each cached account id
        var accounts = store.getAccounts().keySet().stream().map(id -> {
            try {
                Account a = stripe.accounts().retrieve(id);
                return Map.of(
                        "id", a.getId(),
                        "charges_enabled", Boolean.TRUE.equals(a.getChargesEnabled()),
                        "payouts_enabled", Boolean.TRUE.equals(a.getPayoutsEnabled()),
                        "requirements_due", a.getRequirements() != null ? a.getRequirements().getCurrentlyDue() : List.of()
                );
            } catch (Exception e) {
                return Map.of("id", id, "error", e.getMessage());
            }
        }).toList();

        return Map.of(
                "accounts", accounts,
                "rootUrl", config.getRootUrl()
        );
    }

    @PostMapping(value = "/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> createAccount() throws Exception {
        // Controller-only properties, no top-level type
        AccountCreateParams params = AccountCreateParams.builder()
                .setController(AccountCreateParams.Controller.builder()
                        .setFees(AccountCreateParams.Controller.Fees.builder().setPayer(AccountCreateParams.Controller.Fees.Payer.APPLICATION).build())
                        .setLosses(AccountCreateParams.Controller.Losses.builder().setPayments(AccountCreateParams.Controller.Losses.Payments.APPLICATION).build())
                        .setStripeDashboard(AccountCreateParams.Controller.StripeDashboard.builder().setType(AccountCreateParams.Controller.StripeDashboard.Type.EXPRESS).build())
                        .build())
                .build();
        Account account = stripe.accounts().create(params);
        store.getAccounts().put(account.getId(), true);
        // Return only minimal fields to avoid Jackson trying to serialize Stripe internals
        return Map.of("id", account.getId());
    }

    @PostMapping(value = "/accounts/{id}/onboard", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> onboard(@PathVariable("id") String id) throws Exception {
        Account account = stripe.accounts().retrieve(id); // Always fetch latest
        AccountLinkCreateParams linkParams = AccountLinkCreateParams.builder()
                .setAccount(account.getId())
                .setRefreshUrl(config.getRootUrl() + "/refresh")
                .setReturnUrl(config.getRootUrl() + "/return")
                .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .build();
        AccountLink link = stripe.accountLinks().create(linkParams);
        store.getAccounts().put(account.getId(), true);
        return Map.of("url", link.getUrl());
    }

    @PostMapping(value = "/accounts/{id}/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> verifyExistingAccount(@PathVariable("id") String id) throws Exception {
        try {
            // Verify the account exists and retrieve its details
            Account account = stripe.accounts().retrieve(id);
            
            // Add to our local store
            store.getAccounts().put(account.getId(), true);
            
            return Map.of(
                "id", account.getId(),
                "charges_enabled", Boolean.TRUE.equals(account.getChargesEnabled()),
                "payouts_enabled", Boolean.TRUE.equals(account.getPayoutsEnabled()),
                "message", "Compte vérifié et ajouté avec succès"
            );
        } catch (Exception e) {
            return Map.of("error", "Compte introuvable ou inaccessible: " + e.getMessage());
        }
    }

    public record PaymentIntentReq(Long amount, String currency, String connected_account_id, Integer application_fee_amount, String order_id) {}

    @PostMapping(value = "/payments", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> createPaymentIntent(@RequestBody PaymentIntentReq req) throws Exception {
        // Basic validation
        if (req.amount == null || req.amount <= 0) return Map.of("error", "Missing or invalid amount");
        if (req.currency == null || req.currency.isBlank()) return Map.of("error", "Missing currency");
        if (req.connected_account_id == null || req.connected_account_id.isBlank()) return Map.of("error", "Missing connected_account_id");

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(req.amount)
                .setCurrency(req.currency)
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build())
                .setTransferData(PaymentIntentCreateParams.TransferData.builder()
                        .setDestination(req.connected_account_id)
                        .build());

        if (req.application_fee_amount != null && req.application_fee_amount >= 0) {
            builder.setApplicationFeeAmount(req.application_fee_amount.longValue());
        }
        if (req.order_id != null && !req.order_id.isBlank()) {
            builder.putMetadata("order_id", req.order_id);
        }

        PaymentIntent pi = stripe.paymentIntents().create(builder.build());
        return Map.of(
                "id", pi.getId(),
                "client_secret", pi.getClientSecret(),
                "status", pi.getStatus()
        );
    }

    // Simple pages
    @GetMapping("/return") public String ret() { return "return"; }
    @GetMapping("/refresh") public String refresh() { return "refresh"; }
    @GetMapping("/success") public String success() { return "success"; }
}