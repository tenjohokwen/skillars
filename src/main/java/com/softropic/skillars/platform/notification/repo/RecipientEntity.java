package com.softropic.skillars.platform.notification.repo;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * skillars-deferred-111 AC12 (owner decision): {@code email} is now a component of
 * {@code envelope_entity_recipients}' composite primary key {@code (envelope_entity_id, email)}
 * (skillars-deferred-129 AC3 Task 1: corrected stale migration citation — the constraint originally
 * shipped in {@code V137__envelope_entity_recipients_composite_pk.sql}, since squashed into
 * {@code V138__baseline_schema.sql:2362-2363}; the constraint itself is real and unchanged, only the
 * filename this Javadoc cited was stale). This class carries no {@code @Id}/{@code @EmbeddedId} —
 * Hibernate's {@code @ElementCollection} mapping structurally cannot express a composite PK on its
 * join table, so the constraint is DB-only, enforced by the migration alone; Hibernate's own
 * auto-DDL never tries to manage (or duplicate) it. {@code @Column(nullable = false)} below
 * documents the column's real, now-enforced shape — it does not itself change how Hibernate
 * migrates an existing column (its {@code migrateTable} ALTER-only path only adds missing columns,
 * per {@code V136}'s own header note).
 *
 * <p><strong>skillars-deferred-129 AC3 (M9):</strong> this composite PK does NOT give a
 * predicate on {@code email} alone any real index support — {@code envelope_entity_id} is the
 * LEADING column, so a lookup by {@code email} cannot use this B-tree, and no other index on
 * {@code email} exists in the migrations. See {@link EnvelopeEntityRepository#findByRecipientsEmail}
 * for the actual (non-indexed) benefit a targeted query gets over a {@code findAll()} scan.
 */
@Embeddable
public class RecipientEntity implements Serializable {
    private String title;
    private String firstname;
    private String lastname;
    @Column(nullable = false)
    private String email;
    //@Size(min = 2, max = 5)
    private String langKey;
    private String gender;
    // skillars-deferred-113 AC1: per-recipient delivery tracking, added by
    // V140__envelope_entity_recipients_delivered_flag.sql. Lets MailManager.sendEmailSync skip
    // recipients a prior attempt already delivered, instead of re-sending to them on every retry.
    private boolean delivered;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getFirstname() {
        return firstname;
    }

    public void setFirstname(String firstname) {
        this.firstname = firstname;
    }

    public String getLastname() {
        return lastname;
    }

    public void setLastname(String lastname) {
        this.lastname = lastname;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getLangKey() {
        return langKey;
    }

    public void setLangKey(String langKey) {
        this.langKey = langKey;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public boolean isDelivered() {
        return delivered;
    }

    public void setDelivered(boolean delivered) {
        this.delivered = delivered;
    }
}
