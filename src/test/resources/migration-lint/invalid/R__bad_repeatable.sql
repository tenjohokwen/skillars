-- Fixture (AC7 blind spot 2): an R__ repeatable containing a blocking DROP. Repeatables re-run on
-- every checksum change, so this is a rolling-deploy hazard no versioned rule sees.
DROP TABLE main.stale_reporting_view;
