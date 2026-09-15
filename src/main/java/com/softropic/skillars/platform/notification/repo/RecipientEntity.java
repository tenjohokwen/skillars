package com.softropic.skillars.platform.notification.repo;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * skillars-deferred-111 AC12 (owner decision): {@code email} is now a component of
 * {@code envelope_entity_recipients}' composite primary key {@code (envelope_entity_id, email)},
 * added by {@code V137__envelope_entity_recipients_composite_pk.sql}. This class carries no
 * {@code @Id}/{@code @EmbeddedId} — Hibernate's {@code @ElementCollection} mapping structurally
 * cannot express a composite PK on its join table, so the constraint is DB-only, enforced by the
 * migration alone; Hibernate's own auto-DDL never tries to manage (or duplicate) it.
 * {@code @Column(nullable = false)} below documents the column's real, now-enforced shape — it
 * does not itself change how Hibernate migrates an existing column (its {@code migrateTable}
 * ALTER-only path only adds missing columns, per {@code V136}'s own header note).
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
}
