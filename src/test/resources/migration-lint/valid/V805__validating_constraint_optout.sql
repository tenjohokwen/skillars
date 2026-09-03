-- Fixture: constraint validated in one step because the table is empty at deploy time.
-- migration-lint: allow-validating-constraint widget_audit is created empty in this same release
ALTER TABLE main.widget_audit
    ADD CONSTRAINT fk_widget_audit_widget FOREIGN KEY (widget_id) REFERENCES main.widget (id);
