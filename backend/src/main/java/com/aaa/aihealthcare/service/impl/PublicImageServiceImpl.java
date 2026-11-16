package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AvatarValidation;
import com.aaa.aihealthcare.service.PublicImageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

@Service
public class PublicImageServiceImpl implements PublicImageService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FILENAME_CODE_POINTS = 200;
    private static final Pattern SAFE_FILENAME = Pattern.compile(
            "^[\\p{L}\\p{N}_-]+\\.((?i:jpg|jpeg|png|gif|webp))$");
    private static final MediaType IMAGE_WEBP = MediaType.parseMediaType("image/webp");

    private final Path configuredDirectory;

    public PublicImageServiceImpl(
            @Value("${healthcare.common-image-dir:uploads/common}") String directory) {
        this.configuredDirectory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public Optional<PublicImage> load(String filename) {
        if (!isSafeFilename(filename)) {
            return Optional.empty();
        }

        try {
            if (!Files.isDirectory(configuredDirectory)) {
                return Optional.empty();
            }
            Path realDirectory = configuredDirectory.toRealPath();
            Path candidate = configuredDirectory.resolve(filename).normalize();
            if (!candidate.startsWith(configuredDirectory)) {
                return Optional.empty();
            }

            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(realDirectory) || !Files.isRegularFile(realFile)) {
                return Optional.empty();
            }

            byte[] bytes = readBounded(realFile);
            String detectedType = AvatarValidation.extension(bytes);
            if (detectedType == null || !matchesExtension(filename, detectedType)) {
                return Optional.empty();
            }

            return Optional.of(new PublicImage(
                    new ByteArrayResource(bytes), contentType(detectedType)));
        } catch (IOException | SecurityException exception) {
            return Optional.empty();
        }
    }

    private boolean isSafeFilename(String filename) {
        if (filename == null || filename.isBlank()
                || filename.codePointCount(0, filename.length()) > MAX_FILENAME_CODE_POINTS) {
            return false;
        }
        return SAFE_FILENAME.matcher(filename).matches();
    }

    private byte[] readBounded(Path file) throws IOException {
        long size = Files.size(file);
        if (size <= 0 || size > MAX_BYTES) {
            throw new IOException("invalid image size");
        }
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES || input.read() != -1) {
                throw new IOException("invalid image size");
            }
            return bytes;
        }
    }

    private boolean matchesExtension(String filename, String detectedType) {
        Matcher matcher = SAFE_FILENAME.matcher(filename);
        if (!matcher.matches()) {
            return false;
        }
        String extension = matcher.group(1).toLowerCase(Locale.ROOT);
        if ("jpeg".equals(extension)) {
            extension = "jpg";
        }
        return extension.equals(detectedType);
    }

    private MediaType contentType(String detectedType) {
        return switch (detectedType) {
            case "jpg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> IMAGE_WEBP;
            default -> throw new IllegalArgumentException("unsupported image type");
        };
    }
}
