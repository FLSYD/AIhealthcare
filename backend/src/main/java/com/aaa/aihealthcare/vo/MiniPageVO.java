package com.aaa.aihealthcare.vo;

import com.github.pagehelper.PageInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 小程序统一分页响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MiniPageVO<T> {
    private List<T> records;
    private long total;
    private long current;
    private long size;

    public static <T> MiniPageVO<T> from(PageInfo<T> pageInfo) {
        if (pageInfo == null) {
            return new MiniPageVO<>(List.<T>of(), 0L, 0L, 0L);
        }
        return new MiniPageVO<>(pageInfo.getList(), pageInfo.getTotal(),
                pageInfo.getPageNum(), pageInfo.getPageSize());
    }
}
