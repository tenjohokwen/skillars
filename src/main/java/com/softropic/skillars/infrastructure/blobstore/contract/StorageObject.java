package com.softropic.skillars.infrastructure.blobstore.contract;

import java.io.InputStream;

public record StorageObject(InputStream data, StorageObjectMetadata metadata) {
}
