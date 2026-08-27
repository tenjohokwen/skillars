-- Story Deferred-77 AC2: development.performance_reports gains a status lifecycle so listReports can
-- hide a report whose PDF hasn't finished uploading yet — previously the S3 upload ran synchronously
-- inside generateReport's own @Transactional method, holding a DB connection for the whole external
-- I/O call. Moving the upload to an async post-commit handler means the report row now exists before
-- the PDF is actually in S3; without this status gate, listReports could hand out a signed URL to a
-- PDF that doesn't exist yet (or ever, if the async upload fails).

CREATE TYPE development.report_status AS ENUM ('PENDING_UPLOAD', 'READY', 'UPLOAD_FAILED');

ALTER TABLE development.performance_reports
    ALTER COLUMN storage_key DROP NOT NULL,
    ADD COLUMN status development.report_status NOT NULL DEFAULT 'READY';

-- Every existing row was created before this status existed, back when generateReport's synchronous
-- S3-then-DB-save ordering guaranteed storage_key always pointed at a real, already-uploaded object.
UPDATE development.performance_reports SET status = 'READY';
