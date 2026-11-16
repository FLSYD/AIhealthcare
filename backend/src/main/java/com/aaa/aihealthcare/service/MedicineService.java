package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.Medicine;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface MedicineService {

    /**
     * 分页查询，keyword 模糊匹配药品名称，status 可选。
     */
    PageInfo<Medicine> page(String keyword, Integer status, Integer pageNum, Integer pageSize);

    Medicine getById(Long id);

    boolean save(Medicine medicine);

    boolean update(Medicine medicine);

    boolean deleteById(Long id);

    boolean updateStatus(Long id, Integer status);

    /**
     * 下拉/开方用药品列表：仅返回启用状态，可按名称检索。
     */
    List<Medicine> listEnabled(String keyword);
}
