package com.softropic.skillars.infrastructure.blobstore.service;

import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObjectMetadata;

import java.io.InputStream;

public interface StorageService {
    void put(String key, InputStream data, long contentLength, String contentType);
    StorageObject get(String key);
    void delete(String key);
    boolean exists(String key);
    StorageObjectMetadata stat(String key);
    void copy(String sourceKey, String destinationKey);
}
