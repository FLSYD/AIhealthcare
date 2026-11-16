package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AppointmentStatus;
import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.dao.PharmacyDao;
import com.aaa.aihealthcare.service.PharmacyService;
import com.aaa.aihealthcare.vo.PrescriptionVO;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PharmacyServiceImpl implements PharmacyService {

    private final PharmacyDao pharmacyDao;

    public PharmacyServiceImpl(PharmacyDao pharmacyDao) {
        this.pharmacyDao = pharmacyDao;
    }

    @Override
    public PageInfo<PrescriptionVO> page(String status, String keyword, Integer pageNum, Integer pageSize) {
        String normalized = normalizeStatus(status);
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        PageUtils.startPage(pageNum, pageSize);
        try {
            List<PrescriptionVO> list = pharmacyDao.selectPrescriptions(normalized, normalizedKeyword);
            for (PrescriptionVO vo : list) {
                vo.setStatusText(statusText(vo.getStatus()));
            }
            return new PageInfo<>(list);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public PrescriptionVO detail(Long prescriptionId) {
        if (prescriptionId == null || prescriptionId < 1) {
            throw new IllegalArgumentException("处方 id 必须为正整数");
        }
        PrescriptionVO vo = pharmacyDao.selectPrescription(prescriptionId);
        if (vo == null) {
            throw new IllegalArgumentException("处方不存在");
        }
        if (!AppointmentStatus.RX_PAID.equals(vo.getStatus())
                && !AppointmentStatus.RX_DISPENSED.equals(vo.getStatus())) {
            throw new IllegalArgumentException("仅可查看待发药或已发药的处方");
        }
        vo.setStatusText(statusText(vo.getStatus()));
        vo.setItems(pharmacyDao.selectItems(prescriptionId));
        return vo;
    }

    /** 只允许查待发药与已发药两种视图，避免越权看到未付款处方。 */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return AppointmentStatus.RX_PAID;
        }
        String value = status.strip();
        if (AppointmentStatus.RX_PAID.equals(value) || AppointmentStatus.RX_DISPENSED.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("只能查询待发药或已发药的处方");
    }

    private String statusText(String status) {
        if (AppointmentStatus.RX_UNPAID.equals(status)) {
            return "待缴费";
        }
        if (AppointmentStatus.RX_PAID.equals(status)) {
            return "待发药";
        }
        if (AppointmentStatus.RX_DISPENSED.equals(status)) {
            return "已发药";
        }
        if (AppointmentStatus.RX_CANCELLED.equals(status)) {
            return "已取消";
        }
        return status;
    }
}
