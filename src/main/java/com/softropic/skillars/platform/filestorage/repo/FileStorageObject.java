package com.softropic.skillars.platform.filestorage.repo;

import com.softropic.skillars.infrastructure.persistence.AbstractAuditingEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Getter
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "file_storage_objects")
public class FileStorageObject extends AbstractAuditingEntity {

    @Column(name = "key", nullable = false, length = 1024)
    private String key;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "checksum", length = 128)
    private String checksum;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    private Map<String, String> tags;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "bucket", nullable = false)
    private String bucket;

    @Column(name = "upload_confirmed_at")
    private Instant uploadConfirmedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "physical_deleted_at")
    private Instant physicalDeletedAt;
}
