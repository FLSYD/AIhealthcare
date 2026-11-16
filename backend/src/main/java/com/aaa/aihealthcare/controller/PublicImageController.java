package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.AnonymousAccess;
import com.aaa.aihealthcare.service.PublicImageService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicImageController {
    private final PublicImageService publicImageService;

    public PublicImageController(PublicImageService publicImageService) {
        this.publicImageService = publicImageService;
    }

    @AnonymousAccess
    @GetMapping("/uploads/common/{filename}")
    public ResponseEntity<Resource> image(@PathVariable String filename) {
        return publicImageService.load(filename)
                .map(image -> ResponseEntity.ok()
                        .contentType(image.contentType())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(image.resource()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
