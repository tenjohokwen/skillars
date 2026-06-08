package com.softropic.skillars.platform.filestorage.event;

import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import org.springframework.context.ApplicationEvent;

public class StorageObjectConfirmedEvent extends ApplicationEvent {

    private final FileStorageObject storageObject;

    public StorageObjectConfirmedEvent(Object source, FileStorageObject storageObject) {
        super(source);
        this.storageObject = storageObject;
    }

    public FileStorageObject getStorageObject() {
        return storageObject;
    }
}
