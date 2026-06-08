ALTER TABLE main.file_storage_objects
    ADD COLUMN physical_deleted_at TIMESTAMPTZ;

CREATE INDEX idx_storage_objects_deletion
    ON main.file_storage_objects(deleted_at, physical_deleted_at)
    WHERE deleted_at IS NOT NULL;
