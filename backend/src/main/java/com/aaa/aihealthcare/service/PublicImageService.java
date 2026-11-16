package com.aaa.aihealthcare.service;

import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

public interface PublicImageService {
    Optional<PublicImage> load(String filename);

    record PublicImage(Resource resource, MediaType contentType) {
    }
}
