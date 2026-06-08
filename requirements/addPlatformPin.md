Platform Config PIN Field

1. Overview

The platform configuration (PlatformConfig) is extended with an optional pin field. This PIN is an API credential provided by certain mobile providers (currently Orange, as channelUserMsisdn credential) and is required in outbound payment API calls. The admin can set or update the PIN alongside the platform
MSISDN from the Platform Configuration screen.
                                                                                                                                                                                                                                                                                                                       
---                              
2. Data Model

PlatformConfig entity — add a nullable column:

┌────────┬─────────┬──────────────────────────────────────────────┐
│ Column │  Type   │                 Constraints                  │
├────────┼─────────┼──────────────────────────────────────────────┤
│ pin    │ VARCHAR │ nullable, stores AES256-encrypted ciphertext │
└────────┴─────────┴──────────────────────────────────────────────┘

- A Flyway migration must add this column to main.platform_config.
- The column stores the encrypted value only; the plaintext PIN never persists to the database.

PlatformConfigDto (GET response) — add:

┌───────────────┬─────────┬───────────────────────────────────────────────────────────────┐
│     Field     │  Type   │                          Description                          │
├───────────────┼─────────┼───────────────────────────────────────────────────────────────┤
│ pinConfigured │ boolean │ true if a PIN has been set for this provider, false otherwise │
└───────────────┴─────────┴───────────────────────────────────────────────────────────────┘

- The actual PIN value (plaintext or ciphertext) is never returned in this DTO.

  ---                                                                                                                                                                                                                                                                                                                  
3. Encryption

- The PIN must be stored encrypted (AES256) and must be decryptable (reversible encryption — not a hash).
- Use the existing Cryptopher utility (AES256TextEncryptor via Jasypt).
- The encryption secret is sourced from a new config property: skillars.platform.pin-encryption-secret, backed by environment variable PLATFORM_PIN_ENCRYPTION_SECRET.
- Add this property to PayamPlatformProperties.

  ---
4. Backend API Changes

4a. Update endpoint — PUT /v1/admin/platform-config/{provider}

- Request body extends to include an optional pin field (String, nullable).
- The PIN, if provided, is validated before processing:
    - Alphanumeric only ([a-zA-Z0-9])
    - Minimum length: 4 characters
    - Maximum length: 8 characters
    - Return 400 Bad Request with a descriptive error if validation fails.
- If valid, the PIN is encrypted via Cryptopher before being persisted.
- PIN and MSISDN are saved atomically in a single transaction.
- Change detection logic (for email notification — see §6):
    - MSISDN changed: old value differs from new value.
    - PIN changed (for email purposes): a PIN was previously set (non-null) and the new value differs. Setting a PIN for the first time (was null) does not count as a PIN change.
    - Fire event only if at least one of the above conditions is true.

4b. Reveal endpoint — GET /v1/admin/platform-config/{provider}/pin  (new)

- Called exclusively when the admin clicks the eye icon in the UI.
- Decrypts the stored PIN via Cryptopher and returns it in plaintext.
- Returns 404 if no PIN is configured for the provider.
- Response body:

{ "pin": "plaintext-value" }

  ---
5. Frontend Changes (PlatformConfigPage.vue)

5a. Provider card (edit view)

- Add an optional PIN input field to each provider card, below the MSISDN field.
- Use a Quasar q-input with the standard password toggle pattern:
    - Default state on page load: masked (type password).
    - Eye icon toggles between masked and revealed within Quasar's built-in append slot.
    - When the admin clicks the eye to reveal: call GET /v1/admin/platform-config/{provider}/pin to fetch the plaintext PIN; populate the field; start a strict 60-second countdown timer.
    - If the 60 seconds elapse without the admin manually toggling it back to masked, the field automatically reverts to masked and the fetched plaintext value is cleared from component state.
    - If the admin clicks the eye to mask before the timer expires, clear the timer and clear the plaintext value from state immediately.
- The PIN field is always rendered (not conditionally hidden). If pinConfigured is false from the GET response, the field is empty; the admin may enter a new PIN.
- The "Save" button saves both MSISDN and PIN together in one API call.
- If the PIN field is left empty on save, no PIN update is sent (the backend retains the existing value). Make this explicit with placeholder text, e.g. "Leave blank to keep existing PIN".

5b. "Add Provider" dialog

- Add an optional PIN field to the dialog with the same Quasar password toggle pattern.
- No auto-reveal timer in this context (the admin just typed it — they know the value).
- PIN is optional; the dialog's "Add" button must not require a PIN to be filled.

5c. API client (admin.api.js)

- updatePlatformConfig(provider, platformMsisdn, pin) — pin is optional/nullable.
- getPlatformConfigPin(provider) — new, calls GET /v1/admin/platform-config/{provider}/pin.

  ---                              
6. Email Notification

Trigger conditions

An email is sent when the PUT update endpoint detects at least one of:
- The MSISDN value changed.
- A PIN that was previously set has been changed (first-time PIN creation does not trigger an email).

Recipient

skillars.platform.notification-email (single platform-level address).

Content

The email must include:
- Provider name (e.g. ORANGE)
- Which field(s) changed: MSISDN, PIN, or both — stated explicitly (e.g. "MSISDN was updated", "PIN was updated")
- Admin username who made the change (from the security context)
- Timestamp of the change

The actual PIN value must not appear in the email.

Implementation

- Extend PlatformConfigChangedEvent to carry: msisdnChanged (boolean), pinChanged (boolean), changedBy (String).
- Update PlatformConfigEmailListener to use this enriched event.
- Update or replace the PLATFORM_CONFIG_CHANGED email template to render the above fields generically (covering MSISDN-only, PIN-only, and both-changed cases).

  ---                                                                                                                                                                                                                                                                                                                  
7. Summary of Constraints

┌────────────────────────┬──────────────────────────────────────────────────────────────────┐
│        Concern         │                             Decision                             │
├────────────────────────┼───────────────────────────────────────────────────────────────────┤
│ PIN format             │ Alphanumeric, 4–8 characters                                      │
├────────────────────────┼───────────────────────────────────────────────────────────────────┤
│ PIN storage            │ AES256 encrypted via Cryptopher, dedicated env-var key            │                                                                                                                                                                                                                       
├────────────────────────┼───────────────────────────────────────────────────────────────────┤
│ PIN in GET response    │ pinConfigured: boolean only — never the value                     │                                                                                                                                                                                                                       
├────────────────────────┼───────────────────────────────────────────────────────────────────┤                                                                                                                                                                                                                       
│ PIN reveal             │ Dedicated GET /{provider}/pin endpoint, called on eye-icon click  │
├────────────────────────┼───────────────────────────────────────────────────────────────────┤                                                                                                                                                                                                                       
│ Default UI state       │ Masked on page load                                               │
├────────────────────────┼───────────────────────────────────────────────────────────────────┤                                                                                                                                                                                                                       
│ Auto-hide timer        │ Strict 60-second countdown from reveal; no reset on interaction   │
├────────────────────────┼───────────────────────────────────────────────────────────────────┤                                                                                                                                                                                                                       
│ Email on first PIN set │ No email                                                          │
├────────────────────────┼───────────────────────────────────────────────────────────────────┤                                                                                                                                                                                                                       
│ Email on PIN change    │ Yes, if PIN was previously set                                    │
├────────────────────────┼───────────────────────────────────────────────────────────────────┤                                                                                                                                                                                                                       
│ Email content          │ Provider, what changed, admin username, timestamp — no PIN value  │
├────────────────────────┼───────────────────────────────────────────────────────────────────┤                                                                                                                                                                                                                       
│ Role restriction       │ Admin-only (no additional role check beyond existing admin guard) │
└────────────────────────┴───────────────────────────────────────────────────────────────────┘                                                                                                                                                                                                                       
                                   
