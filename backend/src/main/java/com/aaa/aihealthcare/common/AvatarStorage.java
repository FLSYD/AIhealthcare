package com.aaa.aihealthcare.common;

/** 单实例头像写入、绑定与回收共用互斥锁，避免回收与刚提交的绑定交错。 */
public final class AvatarStorage {
    public static final Object LOCK = new Object();
    /** 本地旧头像引用须在持有原管理员SQL锁的事务内检查，避免回收后建立悬空引用。 */
    public static String validateReference(String value, String directory) {
        if (value == null || value.isBlank()) return value;
        try {
            java.net.URI uri = java.net.URI.create(value);
            String path = uri.getPath();
            if (path == null) return value;
            String prefix = path.startsWith("/api/avatar/") ? "/api/avatar/"
                    : path.startsWith("/api/login/avatar/") ? "/api/login/avatar/" : null;
            if (prefix == null) return value;
            String name = path.substring(prefix.length());
            if (!name.matches("[a-f0-9]{32}\\.(jpg|png|gif|webp)")
                    || !java.nio.file.Files.isRegularFile(java.nio.file.Path.of(directory).toAbsolutePath().normalize().resolve(name),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("本地头像文件不存在，请重新上传");
            }
            return "/api/avatar/" + name;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("头像地址无效或文件已回收，请重新上传", exception);
        }
    }
    private AvatarStorage() { }
}
