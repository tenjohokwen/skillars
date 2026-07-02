-- idx_message_reports_message_id and idx_conversation_reports_conversation_id (V66) are
-- redundant: the unique constraints uq_message_reports_message_reporter (message_id, reported_by)
-- and uq_conversation_reports_conv_reporter (conversation_id, reported_by) already provide a
-- leading-column index on message_id / conversation_id respectively.
DROP INDEX IF EXISTS messaging.idx_message_reports_message_id;
DROP INDEX IF EXISTS messaging.idx_conversation_reports_conversation_id;
