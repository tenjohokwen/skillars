-- Fixture (skillars-deferred-92 code review): a migration in a subdirectory of the Flyway location
-- used to be invisible to the lint entirely (Files.list is non-recursive; Flyway's own classpath
-- scan IS recursive). No marker, no lock_timeout.
DROP TABLE main.subdirectory_orphan;
