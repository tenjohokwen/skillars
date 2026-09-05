// Fixture source for MigrationLint.Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP (skillars-deferred-92 AC7.2).
//
// This file is deliberately NOT compiled — it lives under src/test/resources so it is a text corpus
// for the reference scan, nothing more. It exists to make both outcomes of the scan reachable:
//
//   * the column V911 drops appears below, alongside the table name, so
//     invalid/V911__drop_column_marker_but_live_reference.sql must FAIL even though it carries a
//     drop-prepared-in marker — a marker that claims preparation while a reader is still live is the
//     whole failure mode the scan exists to catch;
//   * the column V809 drops appears nowhere in this corpus, so valid/V809__prepared_drop.sql passes.
//
// Do NOT name V809's column anywhere in this file, not even in a comment: the scan is a text search,
// so a mention here — including in prose explaining that it is absent — makes it present. That is
// exactly how the first draft of this fixture failed.
//
// The generic column names the rule's javadoc warns about (`id`, `status`) are included so the
// qualified match — identifier AND table name in the same file — is exercised rather than assumed.
package fixtures;

interface WidgetRepository {
    @Query("SELECT w.obsolete_reading FROM main.widget w WHERE w.status = 'ACTIVE' AND w.id = :id")
    Long findObsoleteReading(Long id);
}
