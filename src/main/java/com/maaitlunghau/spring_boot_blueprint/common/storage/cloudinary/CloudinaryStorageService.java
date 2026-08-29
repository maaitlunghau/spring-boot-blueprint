package com.maaitlunghau.spring_boot_blueprint.common.storage.cloudinary;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.maaitlunghau.spring_boot_blueprint.common.storage.ImageTransform;
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
    public StorageResult upload(MultipartFile file, String folder, ImageTransform transform) {
        try {
            Map<String, Object> options = new HashMap<>();
            options.put("folder", folder);
            options.put("fetch_format", "auto");
            options.put("quality", "auto");

            if (transform != null) {
                options.put("width", transform.width());
                options.put("height", transform.height());
                options.put("crop", "fill");
                if (transform.cropToFace()) {
                    options.put("gravity", "face");
                }
            }

            Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), options);

            return new StorageResult(
                (String) result.get("secure_url"),
                (String) result.get("public_id")
            );
        } catch (Exception e) {
            throw new FileUploadException("Failed to upload file to Cloudinary");
        }
    }

    @Override
    public void delete(String publicId) {
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
        } catch (Exception e) {
            throw new FileUploadException("Failed to delete file from Cloudinary");
        }
    }
}
