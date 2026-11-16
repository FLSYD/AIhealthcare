package com.aaa.aihealthcare.vo;

public record MenuManageRequest(
        String name,
        String path,
        Long parentId,
        String icon,
        Integer sortOrder,
        String roles) {
}
