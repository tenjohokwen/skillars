# The 10 checks, in detail

Each check below follows the same shape: why the bug is real, how to spot it, a minimal before/after, and the fix. Code samples are deliberately generic Spring/JPA — map them onto whatever domain you're auditing rather than pattern-matching on exact class names.

---

## 1. Batch-wide transaction swallows a per-item exception's rollback-only flag

**Why it's real:** Spring's default propagation is `REQUIRED` — an inner `@Transactional` call joins whatever transaction is already open rather than starting its own. If the outer method is also `@Transactional` and wraps a `for` loop, every iteration's "own" transactional call is actually the *same physical transaction*. When that inner call throws an unchecked exception, Spring's `TransactionInterceptor` catches it, marks the whole transaction `rollbackOnly = true`, and rethrows. If the loop's `try/catch` swallows that rethrow and continues, the loop looks like it's making progress — but the transaction is already doomed. When the outer method finally returns normally, Spring tries to commit, sees `rollbackOnly`, and throws `UnexpectedRollbackException` instead — discarding every item's work from that entire run, not just the one that failed.

**Detection heuristic:**
1. Find a `@Scheduled` (or otherwise externally-triggered) method carrying method-level `@Transactional`.
2. Inside it, find a loop that calls another method carrying `@Transactional` with default (`REQUIRED`) propagation.
3. Confirm the loop catches exceptions from that call (a bare `catch` around the call, or the called method itself catches-and-logs internally but a *different* unchecked exception — e.g. an optimistic-lock failure — can still escape from the transaction commit machinery, not just from the visible method body).

If all three are true, this bug exists regardless of how careful the `catch` block looks — the damage happens at the physical-transaction level, below where the `catch` operates.

```java
// ❌ BAD — outer @Transactional + inner @Transactional (REQUIRED) + swallowed exception
@Scheduled(fixedDelay = 60_000)
@Transactional
public void processDueItems() {
    for (Item item : itemRepository.findDue()) {
        try {
            itemProcessor.process(item.getId()); // joins this same transaction
        } catch (Exception e) {
            log.warn("Skipping item {}: {}", item.getId(), e.getMessage());
            // transaction is already rollbackOnly at this point if process() threw
        }
    }
    // commit here throws UnexpectedRollbackException — every prior "successful" item is lost too
}

@Transactional // REQUIRED (default) — joins caller's transaction
public void process(Long itemId) { ... }
```

```java
// ✅ FIXED — select outside any long-lived transaction, process each item in its own transaction
@Scheduled(fixedDelay = 60_000)
public void processDueItems() {
    List<Long> ids = transactionTemplate.execute(status -> itemRepository.findDueIds());
    for (Long id : ids) {
        try {
            processOneInNewTransaction(id);
        } catch (Exception e) {
            log.warn("Skipping item {}: {}", id, e.getMessage());
            // this failure can't touch any other item's committed transaction
        }
    }
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void processOneInNewTransaction(Long itemId) { ... }
```

`TransactionTemplate` per item works just as well as `REQUIRES_NEW` and is often clearer when the per-item unit of work isn't naturally its own service method.

**Fix:** batch-select in its own short transaction (or no transaction, for a simple read), then give each item's work either its own `TransactionTemplate.execute()` call or `REQUIRES_NEW` propagation, so one item's failure is physically isolated to its own transaction.

---

## 2. Batch-load-then-stale-write (TOCTOU) with no re-check before mutating

**Why it's real:** A scheduler selects rows with `WHERE condition = X`, then later — sometimes milliseconds later in the same loop, sometimes in a wholly separate transaction after a slow step — acts on those rows using only their identity (`UPDATE ... WHERE id = ?`, `DELETE ... WHERE id = ?`). If a concurrent, entirely normal request (a user cancels, a payment lands, a status changes) flips `condition` in that gap, the scheduler commits an action justified by data that's no longer true. This isn't exotic — the gap only needs to be nonzero, and a real user-facing system has continuous concurrent writes.

**Detection heuristic:** For every scheduler write path, ask: does it re-verify the *same field* that justified the read, immediately before the write, inside the *same transaction* as the write? Specifically reject:
- A re-fetch that loads the entity again but only uses its id for the mutation (fetching isn't the same as checking).
- A re-check that happens in a transaction separate from the write (another writer can land in between the check and the write).

Accept as safe:
- A conditional `UPDATE ... SET ... WHERE id = ? AND condition = X` — the affected-row count itself is the re-check, done atomically by the database.
- An explicit re-fetch-and-compare of the condition field inside the same transaction that performs the write.

```java
// ❌ BAD — selects by condition, acts on identity only, condition never re-checked
List<Booking> expired = bookingRepository.findByStatusAndExpiresAtBefore(PENDING, now);
for (Booking b : expired) {
    bookingRepository.deleteById(b.getId()); // status could have changed to CONFIRMED by now
}
```

```java
// ✅ FIXED — condition re-verified atomically at write time
@Transactional
public int expireOneBooking(Long bookingId, Instant now) {
    return bookingRepository.deleteByIdAndStatusAndExpiresAtBefore(bookingId, PENDING, now); // 0 or 1 rows affected
}

// or, if you need the entity for side effects (e.g. refund logic) before deleting:
@Transactional
public void expireOneBooking(Long bookingId, Instant now) {
    Booking b = bookingRepository.findById(bookingId).orElse(null);
    if (b == null || b.getStatus() != PENDING || !b.getExpiresAt().isBefore(now)) {
        return; // condition no longer holds — someone else already acted
    }
    bookingRepository.delete(b);
}
```

**Fix:** push the condition into the `WHERE` clause of the write itself (preferred — atomic, no window at all), or re-fetch and re-check the condition field inside the same transaction immediately before mutating.

---

## 3. Missing `@SchedulerLock` where sibling schedulers of the same shape have one

**Why it's real:** ShedLock (or an equivalent distributed lock) exists to stop two instances of the same job running concurrently. If some `@Scheduled` methods in the codebase have it and structurally similar ones don't — same "select rows matching a condition, then mutate them" shape — that's very rarely a deliberate decision; it's usually just an oversight from whoever wrote the newer scheduler. Today it might be masked by a single-instance deployment; it stops being masked the moment the app scales horizontally, and by then nobody remembers which schedulers were "the safe ones."

**Detection heuristic:** From the Pass 1 inventory, group `@Scheduled` methods by shape (read-then-write over a row set; pure read/report; idempotent no-op-safe delete). Within a shape-group, flag any method missing `@SchedulerLock` that a sibling in the same group has, *unless* there's a stated reason (e.g., the operation is naturally idempotent and safe to double-run, or it already has `@Version`-based optimistic locking as a backstop and a retry path for the resulting `OptimisticLockException`).

Separately, for any claim-then-process scheduler (`UPDATE ... SET status = 'CLAIMED' WHERE status = 'PENDING'` followed by `SELECT ... WHERE status = 'CLAIMED'`), check that the read-back query is scoped to *this invocation's* claim — e.g. by a batch/run id or `claimed_by`/`claimed_at` column set in the same statement — rather than reading every row with `status = 'CLAIMED'` table-wide. Without that scoping, two concurrent runs each claim disjoint rows correctly, but then *both* read back and process the full claimed set, including the other run's rows.

```java
// ❌ BAD — no lock, and read-back isn't scoped to this run
@Scheduled(fixedDelay = 30_000)
public void processClaimed() {
    outboxRepository.claimPending(); // UPDATE ... SET status='CLAIMED' WHERE status='PENDING'
    List<OutboxEvent> claimed = outboxRepository.findByStatus(CLAIMED); // sees every run's claims
    claimed.forEach(this::process);
}
```

```java
// ✅ FIXED — locked, and read-back scoped to this invocation's claim marker
@Scheduled(fixedDelay = 30_000)
@SchedulerLock(name = "outboxProcessor", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
public void processClaimed() {
    String runId = UUID.randomUUID().toString();
    outboxRepository.claimPending(runId); // UPDATE ... SET status='CLAIMED', claimed_by=? WHERE status='PENDING'
    List<OutboxEvent> claimed = outboxRepository.findByClaimedBy(runId); // only this run's rows
    claimed.forEach(this::process);
}
```

**Fix:** add `@SchedulerLock` consistent with sibling schedulers of the same shape; scope claim-read-back queries to a per-invocation marker instead of a shared status value.

---

## 4. Lock duration sized as a copy-pasted constant instead of derived from worst-case runtime

**Why it's real:** `lockAtMostFor` exists as a safety net — if the instance holding the lock crashes or hangs, the lock is force-released after this duration so the job isn't stuck forever. If it's set below the job's real worst case (batch size × slowest realistic per-item time, including retries and timeouts against external services), a second instance can legitimately grab the lock and start a second concurrent run while the first is still correctly working — reintroducing exactly the race `@SchedulerLock` was added to prevent, but now it looks safe because there's a lock annotation present.

**Detection heuristic:** For each `@SchedulerLock`, look for a comment (or a named constant with a comment at its definition) showing the arithmetic: batch size × per-item worst-case time + margin. A bare literal (`lockAtMostFor = "PT5M"`) with no derivation, especially one that's identical to an unrelated sibling scheduler's value, is the smell — it usually means the value was copied rather than computed for *this* job's actual batch size and per-item cost.

```java
// ❌ BAD — suspiciously identical to three other schedulers, no derivation
@SchedulerLock(name = "videoLifecycle", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
```

```java
// ✅ FIXED — arithmetic shown, tied to this job's actual batch size and per-item cost
// batchSize=200 * perItemWorstCase=2s (includes 1 retry at 1s timeout) = 400s, +50% margin ≈ 10m
@SchedulerLock(name = "videoLifecycle", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
```

**Fix:** compute `lockAtMostFor` from this scheduler's own batch size and realistic per-item worst case (not a sibling's), and leave the arithmetic in a comment so the next person changing batch size knows to revisit the lock duration too.

---

## 5. Missed write skew

**Why it's real:** Write skew is the isolation anomaly `READ COMMITTED` (Postgres's default) does *not* prevent: two concurrent transactions each read overlapping data, each independently confirms (from its own read) that its own write is safe, and both commit — but the combination violates an invariant that spans both rows. The textbook case: two on-call doctors, each checks "is at least one other doctor on call?", both see "yes" (the other one), both go off-call, now zero are on call. Neither transaction wrote a row the other read, so ordinary row-level locking on the written rows doesn't catch it.

**Detection heuristic:** Look for any check-then-act where the invariant being protected spans *more than one row or entity* — capacity checks (assert count < limit, then insert), mutual-coverage checks (assert at least one other X is still active, then deactivate this one), balance/ledger checks (assert sum across rows ≥ threshold, then debit). For each one, confirm there's a mechanism stronger than default read-committed isolation actually enforcing it: `SERIALIZABLE` isolation with retry-on-conflict, an explicit `SELECT ... FOR UPDATE` that locks the rows the invariant depends on (not just the row being written), or a database constraint that makes the bad state literally unrepresentable (e.g. a partial unique index, a check constraint backed by a trigger).

```java
// ❌ BAD — classic write skew: two concurrent bookings can both pass the capacity check
@Transactional
public void bookSlot(Long slotId, Long userId) {
    int currentBookings = bookingRepository.countBySlotId(slotId); // read
    if (currentBookings >= slot.getCapacity()) throw new SlotFullException();
    bookingRepository.save(new Booking(slotId, userId)); // write — doesn't lock what was read
}
```

```java
// ✅ FIXED — lock the rows the invariant depends on before deciding
@Transactional
public void bookSlot(Long slotId, Long userId) {
    Slot slot = slotRepository.findByIdForUpdate(slotId); // SELECT ... FOR UPDATE
    int currentBookings = bookingRepository.countBySlotId(slotId);
    if (currentBookings >= slot.getCapacity()) throw new SlotFullException();
    bookingRepository.save(new Booking(slotId, userId));
}
// or: a DB-level partial unique index / trigger-backed check constraint that makes
// "capacity exceeded" a constraint violation instead of a race outcome.
```

**Fix:** lock the rows the multi-row invariant reads from (`SELECT ... FOR UPDATE`) before deciding, use `SERIALIZABLE` isolation with retry for the transaction, or push the invariant into a database constraint so it can't be violated regardless of application-level races.

---

## 6. ACID compliance violations

**Why it's real:** "It's inside a `@Transactional` method" doesn't automatically mean atomic, consistent, isolated, and durable — each property can be quietly broken by common mistakes:
- **Atomicity:** a method does a non-transactional side effect (an HTTP call, a file write, publishing to a queue) *before* the transactional write, or vice versa — if one half fails, the two are now inconsistent with each other and there's no compensation.
- **Consistency:** application-level invariants (a state machine's valid transitions, a sum that must balance) aren't backed by a DB constraint, so a bug elsewhere can leave rows in an "impossible" state that the transaction boundary did nothing to prevent.
- **Isolation:** relying on the connection pool's default (`READ_COMMITTED` for Postgres/MySQL InnoDB in most Spring configs) when the actual invariant needs `REPEATABLE_READ` or `SERIALIZABLE` to hold (see check #5).
- **Durability:** not really an application-code concern under a standard RDBMS, but watch for anything that acknowledges success to a caller *before* the commit actually happens (e.g., publishing a "done" event inside the transaction, before commit, using a message broker that isn't itself transactionally coordinated with the DB) — a crash between commit-intent and actual commit can make the ack a lie.

**Detection heuristic:** For each `@Transactional` method, check ordering of transactional vs. non-transactional operations, whether cross-row invariants have DB-level backing, whether the isolation level matches what the invariant actually needs, and whether any "success" signal (event publish, response to caller, webhook fire) happens before the commit is guaranteed.

```java
// ❌ BAD — external call inside the transaction, before the DB write; if the DB write
// fails, the external system already thinks the action succeeded and there's no undo
@Transactional
public void cancelSubscription(Long id) {
    paymentGateway.cancelRemote(id);        // succeeds
    subscription.setStatus(CANCELLED);      // then this throws — DB rolls back, gateway doesn't
    subscriptionRepository.save(subscription);
}
```

```java
// ✅ FIXED — commit the local state first (source of truth), then trigger the external
// effect from a reliable outbox/event-after-commit hook, so the two can be reconciled if one fails
@Transactional
public void cancelSubscription(Long id) {
    subscription.setStatus(CANCELLED);
    subscriptionRepository.save(subscription);
    outboxRepository.save(new OutboxEvent(CANCEL_REMOTE, id)); // same transaction, processed after commit
}
```

**Fix:** order operations so the transactional write is the source of truth and external/non-transactional effects are triggered only after commit (outbox pattern, `@TransactionalEventListener(phase = AFTER_COMMIT)`); back multi-row invariants with DB constraints, not just application checks; pick isolation level deliberately for invariants that need more than read-committed gives.

---

## 7. In-memory accumulation risk from "chunking" inside one outer transaction

**Why it's real:** Splitting a big job into sub-batches (page 1, page 2, page 3...) is often done purely to bound *query* size, while the whole loop still runs inside a single `@Transactional` method or one `TransactionTemplate.execute()` call. Hibernate's persistence context doesn't release entities until the transaction ends (or `entityManager.clear()` is called) — so every "chunk" loaded stays resident in memory for the life of the transaction. At that point the chunking bought nothing: peak memory usage is the same as loading the entire dataset in one shot, just spread across more queries. On a large enough table this is an `OutOfMemoryError` waiting for the day the table crosses some size threshold, and it'll pass code review and testing forever because dev/staging datasets are small.

**Detection heuristic:** Find paged/chunked processing loops (`Pageable`, manual `LIMIT/OFFSET`, "process in batches of N" comments). Check the transaction boundary: is there one `@Transactional`/`TransactionTemplate.execute()` scope wrapping the *entire* multi-chunk loop, or one scope per chunk? If it's the former, this bug is present regardless of how small each individual chunk's query looks — the queries are small, the accumulated *held* memory isn't.

```java
// ❌ BAD — one outer transaction for the whole job; every page's entities pile up
// in the persistence context until the whole thing finally commits
@Transactional
public void reprocessAllRecords() {
    int page = 0;
    Page<Record> batch;
    do {
        batch = recordRepository.findAll(PageRequest.of(page++, 500));
        batch.forEach(this::reprocess); // entities from every prior page are still attached
    } while (batch.hasNext());
}
```

```java
// ✅ FIXED — one transaction per chunk, persistence context cleared between chunks
public void reprocessAllRecords() {
    int page = 0;
    boolean hasNext;
    do {
        hasNext = transactionTemplate.execute(status -> {
            Page<Record> batch = recordRepository.findAll(PageRequest.of(pageNum, 500));
            batch.forEach(this::reprocess);
            entityManager.flush();
            entityManager.clear(); // detach everything before this transaction ends
            return batch.hasNext();
        });
    } while (hasNext);
}
```

**Fix:** one transaction (and one `entityManager.flush()`/`clear()`) per chunk, not one transaction spanning the whole multi-chunk loop — that's what actually bounds peak memory, not the query pagination alone.

---

## 8. General TOCTOU and other transaction anomalies

**Why it's real:** Checks #1–#7 and #9–#10 are the recurring, specific shapes this bug family takes in Spring/JPA code — but time-of-check-to-time-of-use races show up in other guises too: a uniqueness check followed by an insert with no unique constraint backing it (`findByEmail` returns empty, then `save()` — two concurrent signups with the same email both pass the check), a "read current balance, compute new balance, write new balance" sequence with no locking or atomic increment, a cache read used to decide a write without invalidation ordering that accounts for concurrent writers, or a multi-step wizard/state machine where step N's precondition was validated in an earlier request and re-validated nowhere at commit time.

**Detection heuristic:** After running checks #1–#7 and #9–#10, do one more pass looking specifically for: (a) uniqueness/existence checks not backed by a DB unique constraint, (b) read-modify-write sequences on a single row without either a DB-level atomic operation (`UPDATE ... SET x = x + 1`) or row locking, (c) any multi-request workflow where a later step trusts a fact established by an earlier step without re-verifying it at the point of commit.

**Fix:** the general remedy is always one of: push the check into the write itself (atomic conditional update, DB constraint), lock what you read before deciding, or re-verify preconditions at the actual point of commit rather than trusting an earlier read.

---

## 9. N+1 query problem

**Why it's real:** A lazy `@OneToMany`/`@ManyToOne` association fetched inside a loop issues one query per row instead of one query total — invisible with 5 test rows, and a linear-in-dataset-size performance cliff (and, combined with check #7, a memory cliff) in production. It's also relevant to this audit specifically because N+1 loops are frequently where check #1's per-item-transaction pattern and check #2's per-item mutation both live — the same loop shape hosts multiple bugs at once.

**Detection heuristic:** Look for loops (`for`, `.forEach`, stream `.map`) over entities where the loop body accesses a lazy association (`item.getOrders()`, `user.getRoles()`) or calls a repository method keyed by the loop variable (`orderRepository.findByUserId(user.getId())` inside a loop over `users`). Either is one query per iteration where a single batched query would do.

```java
// ❌ BAD — one query per user to fetch their orders
List<User> users = userRepository.findAll();
for (User user : users) {
    List<Order> orders = orderRepository.findByUserId(user.getId()); // N+1
    ...
}
```

```java
// ✅ FIXED — one query, batched by ids
List<User> users = userRepository.findAll();
List<Long> userIds = users.stream().map(User::getId).toList();
Map<Long, List<Order>> ordersByUser = orderRepository.findByUserIdIn(userIds)
    .stream().collect(Collectors.groupingBy(Order::getUserId));
```

Or, when the association is a JPA mapping rather than a separate query: `JOIN FETCH` in the JPQL, an `@EntityGraph`, or `@BatchSize`/`hibernate.default_batch_fetch_size` to turn N+1 into a small, bounded number of `IN (...)` queries.

**Fix:** replace the per-iteration query with a single batched query (`IN` clause, `JOIN FETCH`, `@EntityGraph`) or, when full elimination isn't practical, bound it with `@BatchSize` so it becomes N/batchSize queries instead of N.

---

## 10. Hibernate `MultipleBagFetchException` risk

**Why it's real:** `JOIN FETCH`-ing more than one `List`-typed collection association in a single JPQL/Criteria query multiplies rows (a Cartesian product across the two collections), and Hibernate refuses to deduplicate a `List` (a "bag" — unordered, allows duplicates) the way it can for a `Set`. The result is `org.hibernate.loader.MultipleBagFetchException` thrown at query-execution time — this doesn't corrupt data, but it's a hard runtime failure that only surfaces the first time both associations are non-empty and both get fetched together, so it often escapes review and testing until production data has enough rows in both collections to hit the path.

**Detection heuristic:** Find entities with two or more `List`-typed `@OneToMany`/`@ManyToMany` associations. Then find any JPQL/Criteria query that `JOIN FETCH`es (or an `@EntityGraph` that eagerly includes) more than one of those `List` associations at once.

```java
// ❌ BAD — two List associations fetched together
@Entity
class Order {
    @OneToMany(mappedBy = "order") List<OrderLine> lines;
    @OneToMany(mappedBy = "order") List<OrderNote> notes;
}

@Query("SELECT o FROM Order o JOIN FETCH o.lines JOIN FETCH o.notes WHERE o.id = :id")
Order findWithLinesAndNotes(Long id); // throws MultipleBagFetchException
```

**Do not stop at "change `List` to `Set`."** That's the fix that appears in every quick answer because it makes the exception go away, and it's worth flagging on its own: switching to `Set` silences `MultipleBagFetchException` but the generated SQL still performs the exact same join — the Cartesian product across both collections is still there, Hibernate is just now willing to deduplicate the resulting entity references in memory afterward instead of refusing to run the query at all. For an order with 20 lines and 10 notes, that join still returns `20 × 10 = 200` rows for one order before Hibernate collapses them back down — with several orders selected at once, or larger collections, the row count and memory/DB load can dwarf what the query looks like it's doing. `Set` doesn't fix the performance problem; it just removes the safety rail that used to stop the query from compiling. Treat a `List → Set` "fix" as still needing a look at expected collection sizes and query cardinality — small, bounded, single-parent-at-a-time fetches are fine; multi-row-result-with-multi-collection fetches are not. ([Thorben Janssen](https://thorben-janssen.com/fix-multiplebagfetchexception-hibernate/), [Vlad Mihalcea](https://vladmihalcea.com/hibernate-multiplebagfetchexception/) both walk through the same row-count math.)

```java
// ⚠️ COMPILES, but still a Cartesian product under the hood — only reach for this when
// both collections are known to stay small and the query loads one (or very few) parents
@OneToMany(mappedBy = "order") Set<OrderLine> lines;
@OneToMany(mappedBy = "order") Set<OrderNote> notes;
// SELECT o FROM Order o JOIN FETCH o.lines JOIN FETCH o.notes WHERE o.id = :id
// no longer throws, but still returns lines.size() * notes.size() rows for this order

// ✅ ACTUALLY FIXES the Cartesian product — separate queries, merged via the persistence context
@Query("SELECT o FROM Order o JOIN FETCH o.lines WHERE o.id = :id")
Order findWithLines(Long id);
@Query("SELECT o FROM Order o JOIN FETCH o.notes WHERE o.id = :id")
Order findWithNotes(Long id); // same managed Order instance comes back from the 1st-level cache;
                               // call this second so both collections end up populated on it
```

**Fix, in order of preference:**
1. **Split into separate queries**, one `JOIN FETCH`ed collection per query, relying on Hibernate's persistence context to merge the results onto the same managed entity — this is the only option of the three that actually eliminates the Cartesian product rather than just tolerating it (extra round-trip in exchange for no row multiplication; worth it whenever the collections aren't tiny).
2. **`@Fetch(FetchMode.SUBSELECT)`** on one of the collections, so it loads via its own subselect keyed off the main query instead of a join — avoids both the exception and the per-row join multiplication.
3. **Change to `Set`** only when you've checked that both collections and the number of parent rows loaded together are genuinely small — otherwise it's trading a loud compile-time-ish failure for a silent performance problem that won't show up until a parent entity with unusually large collections gets loaded.
