# connect-demo-spring — Documentation des endpoints Stripe

Cette application Spring Boot démontre une intégration Stripe Connect (mode test) pour:
- Créer et onboarder des comptes connectés Express
- Encaisser des paiements par carte via Payment Intents
  - Destination charge vers un compte connecté, avec commission plateforme (`application_fee_amount`)
  - Paiement sur la plateforme (sans compte connecté)
- Consulter le solde plateforme (disponible/en attente)
- Transférer des fonds de la plateforme vers le compte Djust

Les sections ci-dessous listent les endpoints exposés par l'application, le(s) service(s) Stripe utilisés et leur utilité.

---

## Pré-requis & configuration
- Clés Stripe en mode test dans `src/main/resources/application.yaml`:
  - `stripe.secretKey` (sk_test_...)
  - `stripe.publishableKey` (pk_test_...)
  - `stripe.rootUrl` (ex.: http://localhost:4243)
- SDK: `stripe-java` 26.8.0
- Java 17, Spring Boot 3.3.x

---

## Comptes connectés

### POST `/accounts`
- **Stripe**: `Accounts.create(AccountCreateParams)`
- **But**: Créer un compte connecté Express ("controller-only") avec la capability `transfers` demandée.
- **Utilité**: Préparer un marchand à recevoir des paiements (destination charges) et des transferts.
- **Retour**: `{ id: "acct_..." }`

### POST `/accounts/{id}/onboard`
- **Stripe**:
  - `Accounts.retrieve(id)`
  - `AccountLinks.create(AccountLinkCreateParams)`
- **But**: Générer un lien d'onboarding pour un compte existant.
- **Utilité**: Compléter KYC/requirements et activer les capabilities.
- **Retour**: `{ url }`

### POST `/accounts/{id}/verify`
- **Stripe**: `Accounts.retrieve(id)`
- **But**: Vérifier/attacher un compte connecté existant au store local.
- **Utilité**: Lier un compte créé en dehors de l'app.
- **Retour**: Détails minimaux (charges/payouts_enabled).

### POST `/accounts/djust`
- **Stripe**: `Accounts.create(AccountCreateParams)`
- **But**: Créer le compte Express "Djust" avec `transfers` demandé.
- **Utilité**: Compte de référence pour recevoir des transferts de la plateforme.
- **Retour**: `{ id }`

### POST `/accounts/djust/onboard`
- **Stripe**:
  - `Accounts.retrieve(id)`
  - `AccountLinks.create(AccountLinkCreateParams)`
- **But**: Lien d'onboarding pour Djust.
- **Utilité**: Activer `transfers` et compléter les exigences.
- **Retour**: `{ url }`

### POST `/accounts/djust/request-transfers`
- **Stripe**:
  - `Accounts.update(id, AccountUpdateParams)` (request `transfers`)
  - `AccountLinks.create(AccountLinkCreateParams)` (optionnel)
- **But**: Demander/assurer la capability `transfers` pour Djust.
- **Utilité**: Permettre les transferts vers Djust.
- **Retour**: `{ account_id, transfers_status, onboarding_url }`

### POST `/accounts/djust/verify?id=acct_...`
- **Stripe**: `Accounts.retrieve(id)`
- **But**: Vérifier/associer un compte Djust existant.
- **Utilité**: Reprendre un compte déjà créé.
- **Retour**: Détails min. + message de liaison.

---

## Paiements (Payment Intents)

### GET `/pay`
- **Stripe côté client**: `Stripe(publishableKey)`, `elements.create('payment')`, `stripe.confirmPayment(...)`
- **But**: Page UI pour créer/payer un PaymentIntent.
- **Utilité**: Tester 2 flux: destination charge (Connect) et plateforme.

### POST `/payments` (destination charge — Connect)
- **Stripe**: `PaymentIntents.create(PaymentIntentCreateParams)`
- **Construction**:
  - `addPaymentMethodType("card")` (carte uniquement — évite méthodes retardées)
  - `transfer_data.destination = connected_account_id`
  - `application_fee_amount` si fourni
  - `metadata.order_id` si fourni
- **But**: Créer un PaymentIntent chargé sur le compte connecté, avec commission plateforme.
- **Utilité**: La commission plateforme (`application_fee_amount`) est typiquement disponible plus vite sur le solde plateforme en test.
- **Entrée**: `{ amount, currency, connected_account_id, application_fee_amount?, order_id? }`
- **Retour**: `{ id, client_secret, status }`

### POST `/payments/platform` (paiement plateforme)
- **Stripe**: `PaymentIntents.create(PaymentIntentCreateParams)`
- **Construction**:
  - `addPaymentMethodType("card")`
  - Pas de `transfer_data`
  - `metadata.order_id` si fourni
- **But**: Créer un PaymentIntent encaissé par la plateforme.
- **Utilité**: Créditer directement le solde de la plateforme en test.
- **Entrée**: `{ amount, currency, order_id? }`
- **Retour**: `{ id, client_secret, status }`

### GET `/payments/{id}`
- **Stripe**: `PaymentIntents.retrieve(id)`
- **But**: Récupérer un PaymentIntent pour debug/suivi.
- **Retour**: `{ id, amount, currency, application_fee_amount, status }`

---

## Solde plateforme

### GET `/api/balance`
- **Stripe**: `Balance.retrieve()`
- **But**: Obtenir available/pending bruts.
- **Retour**: `{ available: [...], pending: [...] }`

### GET `/api/state`
- **Stripe**:
  - `Accounts.retrieve(id)` (pour les comptes suivis + Djust)
  - `Balance.retrieve()` (résumé par devise: disponible & en attente)
- **But**: État global pour l'UI d'admin.
- **Retour**: `{ accounts: [...], djust?, rootUrl, platform_balance?, platform_balance_pending? }`

---

## Transferts plateforme → Djust

### POST `/transfers/djust`
- **Stripe**: `Transfers.create(TransferCreateParams)`
- **Validations**:
  - Capabilities Djust `transfers` actives (`Accounts.retrieve(djustId)`)
  - Solde disponible suffisant (`Balance.retrieve()`)
- **But**: Transférer des fonds de la plateforme vers Djust.
- **Entrée**: `{ amount, currency, description? }`
- **Retour**: `{ id, amount, currency, destination }`

---

## Conseils de test (mode test)
- Utiliser les cartes de test (ex.: `4242 4242 4242 4242`).
- Éviter les méthodes de paiement à règlement différé (bancontact, klarna, etc.). Les PaymentIntents sont restreints à **card** dans ce projet pour accélérer la disponibilité.
- Les fonds passent de pending → available automatiquement en ~30–120 secondes.
- Pour Connect, préférer les destination charges avec `application_fee_amount` pour que la commission plateforme arrive plus vite sur le solde.
- Rafraîchir `/api/balance` ou `/api/state` après 1–2 minutes.

---

## Démarrage rapide
```bash
mvn spring-boot:run
# Aller sur http://localhost:4243
```

- Créer un compte connecté: bouton "Create connected account" sur la page d'accueil.
- Onboarder le compte: bouton "Onboard".
- Créer un paiement: section "Créer un paiement (Payment Intent)" puis redirection vers `/pay` pour saisir la carte.
- Transférer vers Djust: section "Transférer des fees à Djust" (après disponibilité des fonds).

---

## Sécurité & limites (démo)
- Aucune authentification n'est implémentée (à ne pas utiliser tel quel en production).
- Les erreurs Stripe sont surfacées de manière simplifiée.
- Le stockage est en mémoire (stateless sur redémarrage).