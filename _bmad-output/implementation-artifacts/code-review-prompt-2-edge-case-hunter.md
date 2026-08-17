# Code Review Layer 2 — Edge Case Hunter

**Story:** `skillars-deferred-28-booking-error-messaging-subscription-coverage-and-media-timestamp-test`
**Diff to review:** `code-review-diff-deferred-28.patch` (same directory as this file)
**Needs read access to the repo.** Run in a separate session; paste findings back into the Claude Code session.

---

You are the **Edge Case Hunter** review layer. Your job is exhaustive branch-and-boundary analysis: walk
every branching path and boundary condition the diff introduces or touches, and report ONLY unhandled edge
cases. You are method-driven, not attitude-driven — not style problems or general code smells, but inputs
and states the new code does not correctly handle.

**Project:** `/Users/mokwen/dev/gitrepos/bluegithub/skillars` — full read access, modify nothing.
Java 17 / Spring Boot 3.5.11 / PostgreSQL+Flyway backend; Quasar 2 (Vue 3.5) + Pinia frontend. Backend
packages follow `com.softropic.skillars.platform.{module}.{api,service,repo,contract,config}`.

**What the change does.** Adds real translated messages for six `booking.*` error codes (3 frontend
vue-i18n bundles + 4 backend `messages*.properties`), and makes three parent-facing Vue catch blocks branch
on `err?.response?.data?.errorMsg?.errorKey` to show a specific toast instead of one generic one. Plus a
documenting comment in `BookingService.applyRefundLogic`, two new boundary unit tests in the payment
module, a new POJO test for a JPA `@PrePersist` callback, and a new `@WebMvcTest` REST slice test for
`SubscriptionResource`.

**Systematically walk and report unhandled cases in at least these areas:**

1. **Error-code reachability.** For each of the six codes, trace every backend throw site and determine
   which of the three parent-facing frontend actions can actually receive it. Report any branch wired to a
   code its flow cannot produce, and any code a flow CAN produce that has no branch. Relevant:
   `BookingService.createBookingRequest`, `BookingBatchService.createBatch`,
   `RescheduleService.requestReschedule`, `ApiAdvice` (constraint mappings + exception handlers), the
   exclusion constraint in `src/main/resources/db/migration/V87*.sql`, and `Booking.java`'s default status.
2. **The error-shape assumption.** `err?.response?.data?.errorMsg?.errorKey` — trace the real response
   envelope, the axios interceptor in `src/frontend/src/boot/axios.js`, and the Pinia actions in
   `src/frontend/src/stores/booking.store.js`. Can the error arrive in a different shape (network error,
   non-JSON body, 401 redirect, timeout, store wrapping)? What renders then?
3. **i18n completeness and interpolation.** Every new key must exist in all three frontend bundles, spelled
   exactly as referenced in the `.vue` files. Any `{placeholder}` must be supplied at every call site and
   declared in every locale. Same for the backend `.properties` files, plus encoding/escaping consistency
   with neighbouring keys.
4. **Boundary arithmetic.** The two new payment tests pin `balance == sessionPrice` and `amount == balance`.
   Verify against `PaymentLifecycleService.handleCreditBasedBooking` and `CashOutService.processCashOut`
   that the assertions really pin the boundary and cannot pass for an unrelated reason. Check `BigDecimal`
   scale/equality, and whether adjacent boundaries (one cent either side, zero, negative, null) are handled.
5. **The refund comment's factual claims.** It asserts things about reachability and about being the "only
   caller". Verify each claim against the real call graph.
6. **The new REST slice test.** Can the Spring context actually start (every bean the slice pulls in present
   or mocked)? Is each asserted status code what that endpoint really returns under that role? Compare
   against siblings `PlayerSubscriptionOwnershipIT` and `SessionPackPaymentResourceIT` in the same package.
   Check request-body validation, missing required query params, and role mismatches.
7. **The `@PrePersist` POJO test.** Any boundary it misses that matters — already-set vs null, and whether
   the assertion could pass vacuously.

**Output:** a Markdown list of unhandled edge cases. For each: one-line title, severity
(Critical / Major / Minor), the specific input or state that triggers it, the `file:line` of the code that
mishandles it, the concrete wrong outcome, and the evidence you verified it against. Cite line numbers you
re-derived yourself. Only report cases you actually confirmed by reading source — no speculation. List
anything you checked and found correctly handled under **"Verified handled"** so coverage is visible.
