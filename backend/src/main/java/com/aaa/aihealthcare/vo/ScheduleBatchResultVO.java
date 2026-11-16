package com.aaa.aihealthcare.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 批量新增排班结果。 */
@Data
public class ScheduleBatchResultVO {
    /** 成功创建数量 */
    private int createdCount;
    /** 因已存在而跳过的数量 */
    private int skippedCount;
    /** 因过去日期被跳过的数量 */
    private int pastSkippedCount;
    /** 跳过的明细，便于前端提示 */
    private List<String> skippedDetails = new ArrayList<>();
}
