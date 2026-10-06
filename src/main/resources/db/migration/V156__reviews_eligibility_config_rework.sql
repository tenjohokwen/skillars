-- skillars-deferred-145: replace reviews.submissionWindowDays (a single upper-bound recency
-- window) with two keys that implement a lower-bound maturity floor plus a rolling edit cooldown.
-- Config key swap only -- no schema change.

DELETE FROM main.platform_config WHERE key = 'reviews.submissionWindowDays';

INSERT INTO main.platform_config (key, value, value_type, description) VALUES ('reviews.minSessionAgeDays', '7', 'LONG', 'Minimum age, in days, a COMPLETED booking must have before it counts toward review eligibility') ON CONFLICT (key) DO NOTHING;
INSERT INTO main.platform_config (key, value, value_type, description) VALUES ('reviews.updateCooldownDays', '30', 'LONG', 'Minimum days since a review''s lastModifiedAt before it can be edited again') ON CONFLICT (key) DO NOTHING;
