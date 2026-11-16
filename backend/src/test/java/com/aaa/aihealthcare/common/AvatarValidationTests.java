package com.aaa.aihealthcare.common;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarValidationTests {

    @Test
    void rejectsWebpWithValidHeaderButMissingImageData() {
        byte[] corrupt = HexFormat.of().parseHex(
                "5249464612000000574542505650384c060000002f0000000000");

        assertNull(AvatarValidation.extension(corrupt));
    }

    @Test
    void acceptsValidWebpOnlyWhenDecoderIsAvailable() {
        byte[] valid = Base64.getDecoder().decode(
                "UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAdQlFKUp/+BiOh/AAA=");

        if (AvatarValidation.supportsWebp()) {
            assertEquals("webp", AvatarValidation.extension(valid));
        } else {
            assertNull(AvatarValidation.extension(valid));
        }
    }

    @Test
    void continuesToAcceptDecodableJpgPngAndGif() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        for (String format : new String[] {"jpg", "png", "gif"}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, format, output));
            assertEquals(format, AvatarValidation.extension(output.toByteArray()));
        }
    }

    @Test
    void rejectsTruncatedImageAndExcessiveDimensions() throws Exception {
        assertNull(AvatarValidation.extension(HexFormat.of().parseHex("89504e470d0a1a0a")));
        BufferedImage image = new BufferedImage(4097, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        assertNull(AvatarValidation.extension(output.toByteArray()));
    }
}
