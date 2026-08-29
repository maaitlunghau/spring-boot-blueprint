package com.maaitlunghau.spring_boot_blueprint.common.storage.cloudinary;

import java.io.IOException;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.maaitlunghau.spring_boot_blueprint.common.storage.StorageResult;
import com.maaitlunghau.spring_boot_blueprint.common.storage.StorageService;
import com.maaitlunghau.spring_boot_blueprint.exception.FileUploadException;

@Service
public class CloudinaryStorageService implements StorageService {

    private final Cloudinary cloudinary;

    public CloudinaryStorageService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    @Override
    public StorageResult upload(MultipartFile file, String folder) {
        try {
            Map<?, ?> result = cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap("folder", folder)
            );

            return new StorageResult(
                (String) result.get("secure_url"),
                (String) result.get("public_id")
            );
        } catch (IOException e) {
            throw new FileUploadException("Failed to upload file to Cloudinary");
        }
    }

    @Override
    public void delete(String publicId) {
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
        } catch (IOException e) {
            throw new FileUploadException("Failed to delete file from Cloudinary");
        }
    }
}
