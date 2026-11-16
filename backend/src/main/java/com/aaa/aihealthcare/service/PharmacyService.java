package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.vo.PrescriptionVO;
import com.github.pagehelper.PageInfo;

/** 配药工作台：只处理已付款待发药的处方。 */
public interface PharmacyService {

    /**
     * 处方列表。
     *
     * @param status  paid=待发药（默认）、dispensed=已发药
     * @param keyword 处方号 / 患者姓名 / 医生姓名
     */
    PageInfo<PrescriptionVO> page(String status, String keyword, Integer pageNum, Integer pageSize);

    /** 处方详情，含药品明细。 */
    PrescriptionVO detail(Long prescriptionId);
}
