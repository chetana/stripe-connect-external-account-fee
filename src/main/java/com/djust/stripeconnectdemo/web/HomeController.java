package com.djust.stripeconnectdemo.web;

import com.djust.stripeconnectdemo.config.StripeConfig;
import com.djust.stripeconnectdemo.service.MemoryStore;
import com.djust.stripeconnectdemo.error.BusinessException;
import com.djust.stripeconnectdemo.error.ErrorCode;
import com.stripe.StripeClient;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.TransferCreateParams;
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
        model.addAttribute("djustAccountId", store.getDjustAccountId());
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

        String djustId = store.getDjustAccountId();
        Map<String, Object> djust = null;
        if (djustId != null && !djustId.isBlank()) {
            try {
                Account a = stripe.accounts().retrieve(djustId);
                djust = Map.of(
                        "id", a.getId(),
                        "charges_enabled", Boolean.TRUE.equals(a.getChargesEnabled()),
                        "payouts_enabled", Boolean.TRUE.equals(a.getPayoutsEnabled())
                );
            } catch (Exception e) {
                djust = Map.of("id", djustId, "error", e.getMessage());
            }
        }

        // Also return platform balance summary per currency (available and pending)
        Map<String, Long> balance = new java.util.LinkedHashMap<>();
        try {
            com.stripe.model.Balance b = stripe.balance().retrieve();
            if (b.getAvailable() != null) {
                for (Object amtObj : b.getAvailable()) {
                    if (amtObj == null) continue;
                    try {
                        var cls = amtObj.getClass();
                        var getCurrency = cls.getMethod("getCurrency");
                        var getAmount = cls.getMethod("getAmount");
                        Object cur = getCurrency.invoke(amtObj);
                        Object am = getAmount.invoke(amtObj);
                        if (cur instanceof String c && am instanceof Number n) {
                            balance.put(c, n.longValue());
                        }
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            // expose error instead of balance map if retrieval fails
            balance = null;
        }

        Map<String, Long> pending = new java.util.LinkedHashMap<>();
        try {
            com.stripe.model.Balance b = stripe.balance().retrieve();
            if (b.getPending() != null) {
                for (Object amtObj : b.getPending()) {
                    if (amtObj == null) continue;
                    try {
                        var cls = amtObj.getClass();
                        var getCurrency = cls.getMethod("getCurrency");
                        var getAmount = cls.getMethod("getAmount");
                        Object cur = getCurrency.invoke(amtObj);
                        Object am = getAmount.invoke(amtObj);
                        if (cur instanceof String c && am instanceof Number n) {
                            pending.put(c, n.longValue());
                        }
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            pending = null;
        }

        String rootUrl = config.getRootUrl();
        if (rootUrl == null) rootUrl = "";
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("accounts", accounts);
        if (djust != null) result.put("djust", djust);
        result.put("rootUrl", rootUrl);
        if (balance != null) result.put("platform_balance", balance);
        if (pending != null) result.put("platform_balance_pending", pending);
        return result;
    }

    @GetMapping(value = "/api/balance", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getPlatformBalance() {
        try {
            com.stripe.model.Balance b = stripe.balance().retrieve();
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("available", b.getAvailable());
            out.put("pending", b.getPending());
            return out;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.STRIPE_API_ERROR, "Unable to retrieve platform balance: " + e.getMessage());
        }
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
                // Request 'transfers' capability so the account can receive transfers
                .setCapabilities(AccountCreateParams.Capabilities.builder()
                        .setTransfers(AccountCreateParams.Capabilities.Transfers.builder().setRequested(true).build())
                        .build())
                .build();
        Account account = stripe.accounts().create(params);
        store.getAccounts().put(account.getId(), true);
        // Return only minimal fields to avoid Jackson trying to serialize Stripe internals
        return Map.of("id", account.getId());
    }

    @PostMapping(value = "/accounts/djust", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> createDjustExpressAccount(@RequestParam(value = "email", required = false) String email,
                                                         @RequestParam(value = "country", required = false) String country) throws Exception {
        String existing = store.getDjustAccountId();
        if (existing != null && !existing.isBlank()) {
            return Map.of("id", existing, "message", "Djust account already exists");
        }
        AccountCreateParams.Builder builder = AccountCreateParams.builder()
                .setController(AccountCreateParams.Controller.builder()
                        .setFees(AccountCreateParams.Controller.Fees.builder().setPayer(AccountCreateParams.Controller.Fees.Payer.APPLICATION).build())
                        .setLosses(AccountCreateParams.Controller.Losses.builder().setPayments(AccountCreateParams.Controller.Losses.Payments.APPLICATION).build())
                        .setStripeDashboard(AccountCreateParams.Controller.StripeDashboard.builder().setType(AccountCreateParams.Controller.StripeDashboard.Type.EXPRESS).build())
                        .build())
                // Request 'transfers' capability for Djust account as well
                .setCapabilities(AccountCreateParams.Capabilities.builder()
                        .setTransfers(AccountCreateParams.Capabilities.Transfers.builder().setRequested(true).build())
                        .build());
        if (country != null && !country.isBlank()) builder.setCountry(country);
        if (email != null && !email.isBlank()) builder.setEmail(email);
        Account account = stripe.accounts().create(builder.build());
        store.setDjustAccountId(account.getId());
        return Map.of("id", account.getId());
    }

    @PostMapping(value = "/accounts/djust/onboard", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> onboardDjust() throws Exception {
        String djustId = store.getDjustAccountId();
        if (djustId == null || djustId.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "Djust account not set");
        Account account = stripe.accounts().retrieve(djustId);
        AccountLinkCreateParams linkParams = AccountLinkCreateParams.builder()
                .setAccount(account.getId())
                .setRefreshUrl(config.getRootUrl() + "/refresh")
                .setReturnUrl(config.getRootUrl() + "/return")
                .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .build();
        AccountLink link = stripe.accountLinks().create(linkParams);
        return Map.of("url", link.getUrl());
    }

    @PostMapping(value = "/accounts/djust/request-transfers", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> requestTransfersCapabilityForDjust() throws Exception {
        String djustId = store.getDjustAccountId();
        if (djustId == null || djustId.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "Djust account not set");

        // Request 'transfers' capability on existing account
        com.stripe.param.AccountUpdateParams updateParams = com.stripe.param.AccountUpdateParams.builder()
                .setCapabilities(com.stripe.param.AccountUpdateParams.Capabilities.builder()
                        .setTransfers(com.stripe.param.AccountUpdateParams.Capabilities.Transfers.builder().setRequested(true).build())
                        .build())
                .build();
        Account updated = stripe.accounts().update(djustId, updateParams);

        // Optionally provide an onboarding link to complete requirements
        AccountLinkCreateParams linkParams = AccountLinkCreateParams.builder()
                .setAccount(updated.getId())
                .setRefreshUrl(config.getRootUrl() + "/refresh")
                .setReturnUrl(config.getRootUrl() + "/return")
                .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .build();
        AccountLink link = stripe.accountLinks().create(linkParams);

        String transfersStatus = updated.getCapabilities() != null ? updated.getCapabilities().getTransfers() : null;
        return Map.of(
                "account_id", updated.getId(),
                "transfers_status", transfersStatus,
                "onboarding_url", link.getUrl()
        );
    }

    @PostMapping(value = "/accounts/djust/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> verifyAndLinkDjust(@RequestParam("id") String id) throws Exception {
        if (id == null || id.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "Missing id");
        if (!id.startsWith("acct_")) throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid account id");
        try {
            Account account = stripe.accounts().retrieve(id);
            store.setDjustAccountId(account.getId());
            return Map.of(
                    "id", account.getId(),
                    "charges_enabled", Boolean.TRUE.equals(account.getChargesEnabled()),
                    "payouts_enabled", Boolean.TRUE.equals(account.getPayoutsEnabled()),
                    "message", "Compte Djust associé avec succès"
            );
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Compte introuvable ou inaccessible: " + e.getMessage());
        }
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
            throw new BusinessException(ErrorCode.NOT_FOUND, "Compte introuvable ou inaccessible: " + e.getMessage());
        }
    }

    public record PaymentIntentReq(Long amount, String currency, String connected_account_id, Integer application_fee_amount, String order_id) {}

    public record TransferReq(Long amount, String currency, String destination_account_id, String description) {}

    @PostMapping(value = "/payments", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> createPaymentIntent(@RequestBody PaymentIntentReq req) throws Exception {
        // Basic validation
        if (req.amount == null || req.amount <= 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "Missing or invalid amount");
        if (req.currency == null || req.currency.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "Missing currency");
        if (req.connected_account_id == null || req.connected_account_id.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "Missing connected_account_id");

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(req.amount)
                .setCurrency(req.currency)
                // Restrict to card to avoid delayed methods (faster availability in test)
                .addPaymentMethodType("card")
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

    @PostMapping(value = "/payments/platform", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> createPlatformPayment(@RequestBody PaymentIntentReq req) throws Exception {
        // Validate minimal fields only (no connected account)
        if (req.amount == null || req.amount <= 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "Missing or invalid amount");
        if (req.currency == null || req.currency.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "Missing currency");

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(req.amount)
                .setCurrency(req.currency)
                // Restrict to card to avoid delayed methods (faster availability in test)
                .addPaymentMethodType("card");

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

    @GetMapping(value = "/payments/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getPaymentIntent(@PathVariable("id") String id) throws Exception {
        PaymentIntent pi = stripe.paymentIntents().retrieve(id);
        return Map.of(
                "id", pi.getId(),
                "amount", pi.getAmount(),
                "currency", pi.getCurrency(),
                "application_fee_amount", pi.getApplicationFeeAmount(),
                "status", pi.getStatus()
        );
    }

    @PostMapping(value = "/transfers/djust", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> transferFeesToDjust(@RequestBody TransferReq req) throws Exception {
        if (req.amount == null || req.amount <= 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "Missing or invalid amount");
        if (req.currency == null || req.currency.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "Missing currency");
        String djustId = store.getDjustAccountId();
        if (djustId == null || djustId.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "Djust account not set");

        // Ensure destination account can receive transfers
        try {
            Account dj = stripe.accounts().retrieve(djustId);
            var cap = dj.getCapabilities();
            boolean transfersOk = cap != null && ("active".equalsIgnoreCase(cap.getTransfers()));
            if (!transfersOk) {
                throw new BusinessException(
                        ErrorCode.ONBOARDING_REQUIRED,
                        "Djust account cannot receive transfers yet (capability 'transfers' not active). Onboard and complete requirements.");
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.STRIPE_API_ERROR, "Unable to check Djust account capabilities: " + e.getMessage());
        }

        // Check platform available balance in the requested currency to avoid balance_insufficient
        try {
            com.stripe.model.Balance balance = stripe.balance().retrieve();
            long available = 0L;
            if (balance.getAvailable() != null) {
                for (Object amtObj : balance.getAvailable()) {
                    if (amtObj == null) continue;
                    try {
                        var cls = amtObj.getClass();
                        var getCurrency = cls.getMethod("getCurrency");
                        var getAmount = cls.getMethod("getAmount");
                        Object cur = getCurrency.invoke(amtObj);
                        Object am = getAmount.invoke(amtObj);
                        if (cur instanceof String c && am instanceof Number n && req.currency.equalsIgnoreCase(c)) {
                            available = n.longValue();
                            break;
                        }
                    } catch (Exception ignored) { }
                }
            }
            if (available < req.amount) {
                throw new BusinessException(
                        ErrorCode.INSUFFICIENT_FUNDS,
                        "Insufficient platform balance in " + req.currency + " (available=" + available + ", requested=" + req.amount + ").",
                        java.util.Map.of(
                                "available_by_currency", java.util.Map.of(req.currency.toLowerCase(), available),
                                "requested", req.amount,
                                "currency", req.currency
                        )
                );
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.STRIPE_API_ERROR, "Unable to retrieve platform balance: " + e.getMessage());
        }

        TransferCreateParams.Builder builder = TransferCreateParams.builder()
                .setAmount(req.amount)
                .setCurrency(req.currency)
                .setDestination(djustId);
        if (req.description != null && !req.description.isBlank()) builder.setDescription(req.description);

        Transfer transfer = stripe.transfers().create(builder.build());
        return Map.of(
                "id", transfer.getId(),
                "amount", transfer.getAmount(),
                "currency", transfer.getCurrency(),
                "destination", transfer.getDestination()
        );
    }

    // Simple pages
    @GetMapping("/return") public String ret() { return "return"; }
    @GetMapping("/refresh") public String refresh() { return "refresh"; }
    @GetMapping("/success") public String success() { return "success"; }
}