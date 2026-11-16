package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.vo.PrescriptionVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 配药工作台查询。 */
public interface PharmacyDao {

    /** 按状态查询处方列表，keyword 支持处方号 / 患者姓名 / 医生姓名。 */
    List<PrescriptionVO> selectPrescriptions(@Param("status") String status,
                                             @Param("keyword") String keyword);

    /** 单个处方（含患者与医生信息），不含明细。 */
    PrescriptionVO selectPrescription(@Param("id") Long id);

    /** 处方药品明细。 */
    List<PrescriptionVO.PrescriptionItemView> selectItems(@Param("prescriptionId") Long prescriptionId);
}
