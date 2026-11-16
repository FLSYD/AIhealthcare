package com.aaa.aihealthcare.common;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public final class AvatarValidation {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SIDE = 4096;
    private static final long MAX_PIXELS = 16_777_216L;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private AvatarValidation() {
    }

    public static boolean supportsWebp() {
        return hasImageReader("webp");
    }

    public static String extension(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            return null;
        }
        try {
            if (startsWith(bytes, PNG_SIGNATURE)) {
                return validPng(bytes) ? "png" : null;
            }
            if (startsWith(bytes, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
                return validJpeg(bytes) ? "jpg" : null;
            }
            if (startsWithAscii(bytes, "GIF87a") || startsWithAscii(bytes, "GIF89a")) {
                return validGif(bytes) ? "gif" : null;
            }
            if (startsWithAscii(bytes, "RIFF") && asciiEquals(bytes, 8, "WEBP")) {
                return validWebp(bytes) ? "webp" : null;
            }
        } catch (Exception exception) {
            // 文件内容不可信，任何格式解析或解码异常都按不支持处理。
            return null;
        }
        return null;
    }

    private static boolean validJpeg(byte[] bytes) throws IOException {
        // EOI 是最容易缺失的收尾标记；ImageIO 再验证实际解码结果。
        if (bytes.length < 4 || (bytes[bytes.length - 2] & 0xff) != 0xff
                || (bytes[bytes.length - 1] & 0xff) != 0xd9) {
            return false;
        }
        return decodable(bytes, "jpeg");
    }

    private static boolean validPng(byte[] bytes) throws IOException {
        if (bytes.length < PNG_SIGNATURE.length + 25) {
            return false;
        }
        boolean hasHeader = false;
        boolean hasImageData = false;
        boolean hasNonEmptyImageData = false;
        int position = PNG_SIGNATURE.length;
        while (position < bytes.length) {
            if (bytes.length - position < 12) {
                return false;
            }
            int length = readIntBigEndian(bytes, position);
            if (length < 0 || length > bytes.length - position - 12) {
                return false;
            }
            if (!validPngChunkType(bytes, position + 4)) {
                return false;
            }
            CRC32 crc = new CRC32();
            crc.update(bytes, position + 4, length + 4);
            long expectedCrc = uint32BigEndian(bytes, position + 8 + length);
            if (crc.getValue() != expectedCrc) {
                return false;
            }

            String type = ascii(bytes, position + 4, 4);
            if (!hasHeader) {
                if (!"IHDR".equals(type) || length != 13) {
                    return false;
                }
                long width = uint32BigEndian(bytes, position + 8);
                long height = uint32BigEndian(bytes, position + 12);
                if (!validDimensions(width, height)) {
                    return false;
                }
                hasHeader = true;
            } else if ("IHDR".equals(type)) {
                return false;
            }
            if ("IDAT".equals(type)) {
                hasImageData = true;
                hasNonEmptyImageData |= length > 0;
            }
            if ("IEND".equals(type)) {
                if (length != 0 || position + 12 != bytes.length
                        || !hasImageData || !hasNonEmptyImageData) {
                    return false;
                }
                return decodable(bytes, "png");
            }
            position += length + 12;
        }
        return false;
    }

    private static boolean validGif(byte[] bytes) throws IOException {
        if (bytes.length < 14 || (bytes[bytes.length - 1] & 0xff) != 0x3b) {
            return false;
        }
        long canvasWidth = readUnsignedShortLittleEndian(bytes, 6);
        long canvasHeight = readUnsignedShortLittleEndian(bytes, 8);
        if (!validDimensions(canvasWidth, canvasHeight)) {
            return false;
        }
        int position = 13;
        if ((bytes[10] & 0x80) != 0) {
            position += 3 * (1 << ((bytes[10] & 7) + 1));
        }
        int frames = 0;
        long pixels = 0;
        while (position < bytes.length) {
            int marker = bytes[position++] & 0xff;
            if (marker == 0x3b) {
                return frames > 0 && position == bytes.length && decodable(bytes, "gif", frames);
            }
            if (marker == 0x21) {
                if (position >= bytes.length) {
                    return false;
                }
                position++; // 扩展标签后均为带长度的子块。
                position = gifSubBlocksEnd(bytes, position);
            } else if (marker == 0x2c) {
                if (bytes.length - position < 9 || ++frames > 256) {
                    return false;
                }
                int x = readUnsignedShortLittleEndian(bytes, position);
                int y = readUnsignedShortLittleEndian(bytes, position + 2);
                int width = readUnsignedShortLittleEndian(bytes, position + 4);
                int height = readUnsignedShortLittleEndian(bytes, position + 6);
                int flags = bytes[position + 8] & 0xff;
                pixels += (long) width * height;
                if (!validDimensions(width, height) || x + width > canvasWidth
                        || y + height > canvasHeight || pixels > MAX_PIXELS) {
                    return false;
                }
                position += 9;
                if ((flags & 0x80) != 0) {
                    position += 3 * (1 << ((flags & 7) + 1));
                }
                if (position >= bytes.length || (bytes[position] & 0xff) < 2
                        || (bytes[position++] & 0xff) > 8 || position >= bytes.length
                        || bytes[position] == 0) {
                    return false;
                }
                position = gifSubBlocksEnd(bytes, position);
            } else {
                return false;
            }
            if (position < 0) {
                return false;
            }
        }
        return false;
    }

    private static int gifSubBlocksEnd(byte[] bytes, int position) {
        while (position < bytes.length) {
            int size = bytes[position++] & 0xff;
            if (size == 0) {
                return position;
            }
            if (size > bytes.length - position) {
                return -1;
            }
            position += size;
        }
        return -1;
    }

    private static boolean validWebp(byte[] bytes) throws IOException {
        if (bytes.length < 20) {
            return false;
        }
        int declaredSize = readIntLittleEndian(bytes, 4);
        if (declaredSize < 4) {
            return false;
        }
        long declaredTotal = 8L + declaredSize;
        if (declaredTotal > bytes.length || declaredTotal > MAX_BYTES) {
            return false;
        }
        if (declaredTotal != bytes.length) {
            return false;
        }

        List<Chunk> chunks = readChunks(bytes, 12, bytes.length);
        int extendedCount = countChunks(chunks, "VP8X");
        if (extendedCount > 1) {
            return false;
        }
        if (extendedCount == 1) {
            if (!"VP8X".equals(chunks.get(0).type())) {
                return false;
            }
            if (!validExtendedWebp(bytes, chunks)) {
                return false;
            }
        } else if (!validSimpleWebp(bytes, chunks)) {
            return false;
        }

        // 文件头和尺寸不能证明图片有效；缺少解码器时拒绝 WEBP，不能跳过内容校验。
        return supportsWebp() && decodable(bytes, "webp");
    }

    private static boolean validSimpleWebp(byte[] bytes, List<Chunk> chunks) throws IOException {
        if (countChunks(chunks, "ANIM") != 0 || countChunks(chunks, "ANMF") != 0
                || countChunks(chunks, "ICCP") != 0 || countChunks(chunks, "EXIF") != 0
                || countChunks(chunks, "XMP ") != 0 || countChunks(chunks, "ALPH") != 0) {
            return false;
        }
        int imageCount = countChunks(chunks, "VP8 ") + countChunks(chunks, "VP8L");
        if (imageCount != 1) {
            return false;
        }
        Chunk image = countChunks(chunks, "VP8 ") == 1
                ? onlyChunk(chunks, "VP8 ") : onlyChunk(chunks, "VP8L");
        ImageFrame frame = parseImageFrame(bytes, image);
        return frame != null;
    }

    private static boolean validExtendedWebp(byte[] bytes, List<Chunk> chunks) throws IOException {
        Chunk extended = onlyChunk(chunks, "VP8X");
        if (extended.size() != 10) {
            return false;
        }
        int payload = extended.payloadOffset();
        int flags = bytes[payload] & 0xff;
        if ((flags & 0xc1) != 0 || bytes[payload + 1] != 0
                || bytes[payload + 2] != 0 || bytes[payload + 3] != 0) {
            return false;
        }
        boolean hasIcc = (flags & 0x20) != 0;
        boolean hasExif = (flags & 0x08) != 0;
        boolean hasXmp = (flags & 0x04) != 0;
        boolean animated = (flags & 0x02) != 0;
        if (!metadataMatches(chunks, "ICCP", hasIcc)
                || !metadataMatches(chunks, "EXIF", hasExif)
                || !metadataMatches(chunks, "XMP ", hasXmp)) {
            return false;
        }

        long canvasWidth = readUnsignedInt24LittleEndian(bytes, payload + 4) + 1;
        long canvasHeight = readUnsignedInt24LittleEndian(bytes, payload + 7) + 1;
        if (!validDimensions(canvasWidth, canvasHeight)) {
            return false;
        }

        if (animated) {
            if (countChunks(chunks, "ANIM") != 1 || countChunks(chunks, "ANMF") == 0
                    || countChunks(chunks, "VP8 ") != 0 || countChunks(chunks, "VP8L") != 0
                    || countChunks(chunks, "ALPH") != 0) {
                return false;
            }
            if (onlyChunk(chunks, "ANIM").size() != 6) {
                return false;
            }
            for (Chunk frame : chunks) {
                if ("ANMF".equals(frame.type())
                        && !validAnimationFrame(bytes, frame, canvasWidth, canvasHeight)) {
                    return false;
                }
            }
            return true;
        }

        if (countChunks(chunks, "ANIM") != 0 || countChunks(chunks, "ANMF") != 0) {
            return false;
        }
        int imageCount = countChunks(chunks, "VP8 ") + countChunks(chunks, "VP8L");
        if (imageCount != 1) {
            return false;
        }
        Chunk image = countChunks(chunks, "VP8 ") == 1
                ? onlyChunk(chunks, "VP8 ") : onlyChunk(chunks, "VP8L");
        ImageFrame frame = parseImageFrame(bytes, image);
        return frame != null && frame.width() == canvasWidth && frame.height() == canvasHeight
                && validAlpha(bytes, chunks, image, frame)
                && (!"VP8 ".equals(image.type())
                    || ((flags & 0x10) != 0) == (countChunks(chunks, "ALPH") == 1));
    }

    private static boolean metadataMatches(List<Chunk> chunks, String type, boolean flagged)
            throws IOException {
        int count = countChunks(chunks, type);
        return flagged ? count == 1 : count == 0;
    }

    private static boolean validAnimationFrame(
            byte[] bytes, Chunk chunk, long canvasWidth, long canvasHeight) throws IOException {
        if (chunk.size() < 16) {
            return false;
        }
        int payload = chunk.payloadOffset();
        long x = readUnsignedInt24LittleEndian(bytes, payload) * 2L;
        long y = readUnsignedInt24LittleEndian(bytes, payload + 3) * 2L;
        long width = readUnsignedInt24LittleEndian(bytes, payload + 6) + 1;
        long height = readUnsignedInt24LittleEndian(bytes, payload + 9) + 1;
        int flags = bytes[payload + 15] & 0xff;
        if ((flags & 0xfc) != 0 || !validDimensions(width, height)
                || x + width > canvasWidth || y + height > canvasHeight) {
            return false;
        }

        List<Chunk> frameChunks = readChunks(bytes, payload + 16, payload + chunk.size());
        int imageCount = countChunks(frameChunks, "VP8 ") + countChunks(frameChunks, "VP8L");
        if (imageCount != 1) {
            return false;
        }
        Chunk image = countChunks(frameChunks, "VP8 ") == 1
                ? onlyChunk(frameChunks, "VP8 ") : onlyChunk(frameChunks, "VP8L");
        ImageFrame frame = parseImageFrame(bytes, image);
        return frame != null && frame.width() == width && frame.height() == height
                && validAlpha(bytes, frameChunks, image, frame);
    }

    private static boolean validAlpha(byte[] bytes, List<Chunk> chunks, Chunk image, ImageFrame frame)
            throws IOException {
        int count = countChunks(chunks, "ALPH");
        if (count == 0) {
            return true;
        }
        if (count != 1 || !"VP8 ".equals(image.type())) {
            return false;
        }
        Chunk alpha = onlyChunk(chunks, "ALPH");
        if (alpha.size() < 2 || chunks.indexOf(alpha) + 1 != chunks.indexOf(image)) {
            return false;
        }
        int header = bytes[alpha.payloadOffset()] & 0xff;
        int compression = header & 3;
        if ((header & 0xc0) != 0 || compression > 1 || ((header >>> 4) & 3) > 1) {
            return false;
        }
        return compression == 1 || alpha.size() - 1L == frame.width() * frame.height();
    }

    private static ImageFrame parseImageFrame(byte[] bytes, Chunk chunk) throws IOException {
        if ("VP8 ".equals(chunk.type())) {
            return parseVp8Frame(bytes, chunk);
        }
        if ("VP8L".equals(chunk.type())) {
            return parseVp8lFrame(bytes, chunk);
        }
        return null;
    }

    private static ImageFrame parseVp8Frame(byte[] bytes, Chunk chunk) throws IOException {
        if (chunk.size() < 12) {
            return null;
        }
        int position = chunk.payloadOffset();
        int tag = readUnsignedInt24LittleEndian(bytes, position);
        int version = (tag >>> 1) & 0x07;
        int firstPartitionSize = tag >>> 5;
        if ((tag & 0x01) != 0 || version > 3 || (tag & 0x10) == 0
                || firstPartitionSize == 0 || 10L + firstPartitionSize >= chunk.size()
                || (bytes[position + 3] & 0xff) != 0x9d
                || (bytes[position + 4] & 0xff) != 0x01
                || (bytes[position + 5] & 0xff) != 0x2a) {
            return null;
        }
        int widthBits = readUnsignedShortLittleEndian(bytes, position + 6);
        int heightBits = readUnsignedShortLittleEndian(bytes, position + 8);
        if ((widthBits & 0xc000) != 0 || (heightBits & 0xc000) != 0) {
            return null;
        }
        long width = widthBits & 0x3fff;
        long height = heightBits & 0x3fff;
        return validDimensions(width, height) ? new ImageFrame(width, height) : null;
    }

    private static ImageFrame parseVp8lFrame(byte[] bytes, Chunk chunk) throws IOException {
        if (chunk.size() < 6) {
            return null;
        }
        int position = chunk.payloadOffset();
        if ((bytes[position] & 0xff) != 0x2f) {
            return null;
        }
        int bits = readIntLittleEndian(bytes, position + 1);
        if (((bits >>> 29) & 0x07) != 0) {
            return null;
        }
        long width = (bits & 0x3fff) + 1L;
        long height = ((bits >>> 14) & 0x3fff) + 1L;
        return validDimensions(width, height) ? new ImageFrame(width, height) : null;
    }

    private static List<Chunk> readChunks(byte[] bytes, int start, int end) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        int position = start;
        while (position < end) {
            if (end - position < 8) {
                throw new IOException("RIFF chunk header truncated");
            }
            int size = readIntLittleEndian(bytes, position + 4);
            if (size < 0) {
                throw new IOException("RIFF chunk size invalid");
            }
            long payloadEnd = (long) position + 8 + size;
            if (payloadEnd > end) {
                throw new IOException("RIFF chunk exceeds container");
            }
            String type = ascii(bytes, position, 4);
            if (!validFourCc(bytes, position)) {
                throw new IOException("RIFF chunk type invalid");
            }
            int next = (int) payloadEnd;
            if ((size & 1) != 0) {
                if (next >= end || bytes[next] != 0) {
                    throw new IOException("RIFF chunk padding invalid");
                }
                next++;
            }
            chunks.add(new Chunk(type, position + 8, size));
            position = next;
        }
        if (position != end) {
            throw new IOException("RIFF chunk boundary invalid");
        }
        return chunks;
    }

    private static boolean decodable(byte[] bytes, String format) throws IOException {
        return decodable(bytes, format, 1);
    }

    private static boolean decodable(byte[] bytes, String format, int frames) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        Exception failure = null;
        if (!readers.hasNext()) {
            throw new IOException("No ImageIO reader for " + format);
        }
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            try (ImageInputStream input = ImageIO.createImageInputStream(
                    new ByteArrayInputStream(bytes))) {
                if (input == null) {
                    throw new IOException("Image stream unavailable");
                }
                reader.setInput(input, false, true);
                boolean[] corrupt = {false};
                reader.addIIOReadWarningListener((source, warning) -> {
                    String message = warning.toLowerCase(Locale.ROOT);
                    if (message.contains("corrupt") || message.contains("premature")
                            || message.contains("truncat")) {
                        corrupt[0] = true;
                    }
                });
                if (frames > 1 && reader.getNumImages(true) != frames) {
                    throw new IOException("Image frame count invalid");
                }
                long pixels = 0;
                for (int index = 0; index < frames; index++) {
                    int width = reader.getWidth(index);
                    int height = reader.getHeight(index);
                    pixels += (long) width * height;
                    if (!validDimensions(width, height) || pixels > MAX_PIXELS) {
                        throw new IOException("Image dimensions exceed limit");
                    }
                    BufferedImage image = reader.read(index);
                    if (corrupt[0] || image == null || image.getWidth() != width || image.getHeight() != height) {
                        throw new IOException("Image decode result invalid");
                    }
                    image.flush();
                }
                return true;
            } catch (Exception exception) {
                failure = exception;
            } finally {
                reader.dispose();
            }
        }
        throw new IOException("Image decode failed", failure);
    }

    private static boolean hasImageReader(String format) {
        return ImageIO.getImageReadersByFormatName(format).hasNext();
    }

    private static int countChunks(List<Chunk> chunks, String type) {
        int count = 0;
        for (Chunk chunk : chunks) {
            if (type.equals(chunk.type())) {
                count++;
            }
        }
        return count;
    }

    private static Chunk onlyChunk(List<Chunk> chunks, String type) throws IOException {
        Chunk result = null;
        for (Chunk chunk : chunks) {
            if (type.equals(chunk.type())) {
                if (result != null) {
                    throw new IOException("Duplicate RIFF chunk");
                }
                result = chunk;
            }
        }
        if (result == null) {
            throw new IOException("Required RIFF chunk missing");
        }
        return result;
    }

    private static boolean validDimensions(long width, long height) {
        return width > 0 && height > 0 && width <= MAX_SIDE && height <= MAX_SIDE
                && width * height <= MAX_PIXELS;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] bytes, String prefix) {
        return asciiEquals(bytes, 0, prefix);
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String value) {
        if (offset < 0 || bytes.length - offset < value.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (bytes[offset + index] != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static boolean validPngChunkType(byte[] bytes, int offset) {
        for (int index = 0; index < 4; index++) {
            int value = bytes[offset + index] & 0xff;
            if (!((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validFourCc(byte[] bytes, int offset) {
        if (offset < 0 || bytes.length - offset < 4) {
            return false;
        }
        for (int index = 0; index < 4; index++) {
            int value = bytes[offset + index] & 0xff;
            if (value < 0x20 || value > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static int readIntBigEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
                | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8
                | bytes[offset + 3] & 0xff;
    }

    private static long uint32BigEndian(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(readIntBigEndian(bytes, offset));
    }

    private static int readIntLittleEndian(byte[] bytes, int offset) {
        return bytes[offset] & 0xff
                | (bytes[offset + 1] & 0xff) << 8
                | (bytes[offset + 2] & 0xff) << 16
                | (bytes[offset + 3] & 0xff) << 24;
    }

    private static int readUnsignedShortLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8;
    }

    private static int readUnsignedInt24LittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | (bytes[offset + 1] & 0xff) << 8
                | (bytes[offset + 2] & 0xff) << 16;
    }

    private record Chunk(String type, int payloadOffset, int size) {
    }

    private record ImageFrame(long width, long height) {
    }
}
