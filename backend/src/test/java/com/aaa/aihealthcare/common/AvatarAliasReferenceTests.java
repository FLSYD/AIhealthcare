package com.aaa.aihealthcare.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** BE02：旧别名 /api/login/avatar/ 与标准路径一样必须核实本地文件，并规范为 /api/avatar/。 */
class AvatarAliasReferenceTests {
    @TempDir Path directory;

    @Test
    void missingFileBehindLegacyAliasIsRejected() {
        String name = "c".repeat(32) + ".png";
        assertThrows(IllegalArgumentException.class,
                () -> AvatarStorage.validateReference("/api/login/avatar/" + name, directory.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> AvatarStorage.validateReference("/api/avatar/" + name, directory.toString()));
    }

    @Test
    void existingFileBehindLegacyAliasIsNormalizedAndKeepsQueryFragmentSemantics() throws Exception {
        String name = "d".repeat(32) + ".png";
        Files.writeString(directory.resolve(name), "synthetic");
        assertEquals("/api/avatar/" + name,
                AvatarStorage.validateReference("/api/login/avatar/" + name, directory.toString()));
        assertEquals("/api/avatar/" + name,
                AvatarStorage.validateReference("/api/login/avatar/" + name + "?v=1#photo", directory.toString()));
        assertEquals("/api/avatar/" + name,
                AvatarStorage.validateReference("/api/avatar/" + name + "?v=1#photo", directory.toString()));
    }

    @Test
    void invalidAliasNameIsRejectedAndForeignUrlsPassThrough() {
        assertThrows(IllegalArgumentException.class,
                () -> AvatarStorage.validateReference("/api/login/avatar/../x.png", directory.toString()));
        assertEquals("https://example.invalid/a.png",
                AvatarStorage.validateReference("https://example.invalid/a.png", directory.toString()));
    }
}
