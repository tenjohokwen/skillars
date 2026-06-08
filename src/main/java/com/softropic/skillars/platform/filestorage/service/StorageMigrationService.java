package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageProviderException;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.infrastructure.persistence.EntityStatus;
import com.softropic.skillars.platform.filestorage.contract.StorageObjectDto;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Slf4j
public class StorageMigrationService {

    private final StorageService source;
    private final StorageService destination;
    private final FileStorageObjectRepository fileStorageObjectRepository;

    public StorageMigrationService(StorageService source, StorageService destination,
                                   FileStorageObjectRepository fileStorageObjectRepository) {
        this.source = source;
        this.destination = destination;
        this.fileStorageObjectRepository = fileStorageObjectRepository;
    }

    public void migrate(String sourceKey, String destinationKey) {
        StorageObject obj = source.get(sourceKey);
        try (InputStream data = obj.data()) {
            destination.put(destinationKey, data, obj.metadata().contentLength(), obj.metadata().contentType());
            log.info("Migrated: {} -> {}", sourceKey, destinationKey);
        } catch (IOException e) {
            throw new StorageProviderException("migrate", e, sourceKey + "->" + destinationKey);
        }
    }

    public void migrateAll(String ownerId) {
        List<FileStorageObject> objects = fileStorageObjectRepository.findAllByOwnerIdAndDeletedAtIsNull(ownerId);
        log.info("Starting migration for owner {}, {} objects", ownerId, objects.size());
        for (FileStorageObject fso : objects) {
            if (destination.exists(fso.getKey())) {
                log.info("Skipping already-migrated key: {}", fso.getKey());
                continue;
            }
            try {
                migrate(fso.getKey(), fso.getKey());
            } catch (Exception ex) {
                log.error("Failed to migrate key: {}, error: {}", fso.getKey(), ex.getMessage());
            }
        }
        log.info("Migration complete for owner {}", ownerId);
    }

    public List<StorageObjectDto> exportMetadata(String ownerId) {
        return fileStorageObjectRepository.findAllByOwnerIdAndDeletedAtIsNull(ownerId)
            .stream()
            .map(fso -> new StorageObjectDto(
                fso.getKey(),
                fso.getOwnerId(),
                fso.getOriginalFilename(),
                fso.getContentType(),
                fso.getSizeBytes(),
                fso.getChecksum(),
                fso.getTags(),
                fso.getProvider(),
                fso.getBucket(),
                fso.getUploadConfirmedAt()
            ))
            .toList();
    }

    public void importMetadata(List<StorageObjectDto> dtos) {
        for (StorageObjectDto dto : dtos) {
            if (fileStorageObjectRepository.findByKey(dto.key()).isPresent()) {
                log.info("Skipping existing key: {}", dto.key());
                continue;
            }
            FileStorageObject fso = FileStorageObject.builder()
                .key(dto.key())
                .ownerId(dto.ownerId())
                .originalFilename(dto.originalFilename())
                .contentType(dto.contentType())
                .sizeBytes(dto.sizeBytes())
                .checksum(dto.checksum())
                .tags(dto.tags())
                .provider(dto.provider())
                .bucket(dto.bucket())
                .uploadConfirmedAt(dto.uploadConfirmedAt())
                .status(EntityStatus.ACTIVE)
                .build();
            fileStorageObjectRepository.save(fso);
        }
    }
}
