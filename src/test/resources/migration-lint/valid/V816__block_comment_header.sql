/* Fixture (skillars-deferred-92 code review): a block-comment header must count as a header block
   the same as a line-comment one does — BARE_DROP_NO_HEADER used to accept only '--'. */
-- migration-lint: drop-prepared-in: V806
SET lock_timeout = '5s';
DROP TABLE IF EXISTS main.block_comment_header_fixture;
