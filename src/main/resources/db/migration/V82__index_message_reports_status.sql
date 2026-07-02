-- deleteOldMessagesWithNoOpenReports (MessageRepository) filters message_reports by status,
-- not by the message_id leading column of uq_message_reports_message_reporter, so every
-- retention sweep would otherwise scan message_reports in full.
CREATE INDEX idx_message_reports_status ON messaging.message_reports(status);
