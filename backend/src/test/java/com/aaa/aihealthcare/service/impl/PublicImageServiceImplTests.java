package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.service.PublicImageService.PublicImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PublicImageServiceImplTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsValidImageAndDetectsTypeFromContent() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("common"));
        byte[] png = imageBytes("png");
        Files.write(directory.resolve("doctor_avatar-123.png"), png);
        PublicImageServiceImpl service = new PublicImageServiceImpl(directory.toString());

        Optional<PublicImage> result = service.load("doctor_avatar-123.png");

        assertTrue(result.isPresent());
        assertEquals(MediaType.IMAGE_PNG, result.orElseThrow().contentType());
        assertArrayEquals(png, result.orElseThrow().resource().getContentAsByteArray());
    }

    @Test
    void acceptsJpegExtensionWhenContentIsJpeg() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("common"));
        Files.write(directory.resolve("original-photo.jpeg"), imageBytes("jpg"));
        PublicImageServiceImpl service = new PublicImageServiceImpl(directory.toString());

        Optional<PublicImage> result = service.load("original-photo.jpeg");

        assertTrue(result.isPresent());
        assertEquals(MediaType.IMAGE_JPEG, result.orElseThrow().contentType());
    }

    @Test
    void rejectsTraversalFilenamesAndLongNames() {
        PublicImageServiceImpl service = new PublicImageServiceImpl(temporaryDirectory.toString());

        assertFalse(service.load("../secret.png").isPresent());
        assertFalse(service.load("..\\secret.png").isPresent());
        assertFalse(service.load("dir/secret.png").isPresent());
        assertFalse(service.load("a".repeat(197) + ".png").isPresent());
    }

    @Test
    void rejectsMissingNonImageAndMismatchedExtension() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("common"));
        Files.writeString(directory.resolve("not-image.png"), "not an image");
        Files.write(directory.resolve("mismatch.jpg"), imageBytes("png"));
        PublicImageServiceImpl service = new PublicImageServiceImpl(directory.toString());

        assertFalse(service.load("missing.png").isPresent());
        assertFalse(service.load("not-image.png").isPresent());
        assertFalse(service.load("mismatch.jpg").isPresent());
    }

    @Test
    void rejectsSymbolicLinkThatEscapesImageDirectory() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("common"));
        Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Files.write(outsideDirectory.resolve("outside.png"), imageBytes("png"));
        Path link = directory.resolve("escaped.png");
        try {
            Files.createSymbolicLink(link, outsideDirectory.resolve("outside.png"));
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "当前环境不支持创建符号链接: " + exception.getMessage());
        }
        PublicImageServiceImpl service = new PublicImageServiceImpl(directory.toString());

        assertFalse(service.load("escaped.png").isPresent());
    }

    private byte[] imageBytes(String format) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }
}
