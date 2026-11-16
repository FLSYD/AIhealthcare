package com.aaa.aihealthcare.vo;

public record MenuVO(String key, String name, String path, String icon, String roles,
        Long id, Long parentId, Integer sortOrder) {
    public MenuVO(String key, String name, String path) {
        this(key, name, path, null, null, null, null, null);
    }
}
