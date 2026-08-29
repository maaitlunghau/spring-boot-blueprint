package com.maaitlunghau.spring_boot_blueprint.common.storage;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

    default StorageResult upload(MultipartFile file, String folder) {
        return upload(file, folder, null);
    }

    StorageResult upload(MultipartFile file, String folder, ImageTransform transform);

    void delete(String publicId);
}
