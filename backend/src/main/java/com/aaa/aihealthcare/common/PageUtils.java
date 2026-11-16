package com.aaa.aihealthcare.common;

import com.github.pagehelper.PageHelper;

public final class PageUtils {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private PageUtils() {
    }

    public static void startPage(Integer pageNum, Integer pageSize) {
        int num = pageNum == null || pageNum < 1 ? DEFAULT_PAGE_NUM : pageNum;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        PageHelper.startPage(num, size);
    }
}