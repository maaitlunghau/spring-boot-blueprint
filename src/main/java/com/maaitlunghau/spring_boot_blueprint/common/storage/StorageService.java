package com.maaitlunghau.spring_boot_blueprint.common.storage;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

    StorageResult upload(MultipartFile file, String folder);

    void delete(String publicId);
}
