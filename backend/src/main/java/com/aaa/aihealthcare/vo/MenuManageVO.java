package com.aaa.aihealthcare.vo;

public record MenuManageVO(
        Long id,
        Long parentId,
        String name,
        String path,
        String icon,
        Integer sortOrder,
        String roles) {
}
