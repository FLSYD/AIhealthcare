package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.vo.AppointmentVO;
import com.github.pagehelper.PageInfo;

public interface AppointmentService {

    /**
     * 分页查询预约列表，支持就诊人/手机号关键词、状态、就诊日期筛选。
     */
    PageInfo<AppointmentVO> page(String keyword, String status, String scheduleDate,
                                 Integer pageNum, Integer pageSize);

    AppointmentVO getDetailById(Long id);

    /**
     * 取消预约：释放号源、回滚余额，仅允许待支付/待就诊/已确认/已到院。
     */
    boolean cancel(Long id);

    /** 仅关闭仍未付款且已超过付款有效期的预约，重复执行不重复释放号源。 */
    boolean expireUnpaid(Long id);

    /**
     * 标记完成：仅允许待就诊/已确认/就诊中。
     */
    boolean complete(Long id);

    /**
     * 标记取药：处方必须已支付，扣减库存并置为已发药，预约转为已完成。
     */
    boolean dispense(Long prescriptionId);
}
