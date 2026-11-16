package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.dao.MedicineDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Medicine;
import com.aaa.aihealthcare.service.MedicineService;
import com.aaa.aihealthcare.payment.NativePaymentGuard;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class MedicineServiceImpl implements MedicineService {

    private static final BigDecimal MAX_DECIMAL_10_2 = new BigDecimal("99999999.99");

    @Autowired
    private MedicineDao medicineDao;

    @Autowired
    private SysUserDao sysUserDao;

    @Autowired(required=false)
    private NativePaymentGuard nativePaymentGuard;

    @Override
    public PageInfo<Medicine> page(String keyword, Integer status, Integer pageNum, Integer pageSize) {
        PageUtils.startPage(pageNum, pageSize);
        try {
            LambdaQueryWrapper<Medicine> wrapper = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(keyword)) {
                wrapper.like(Medicine::getName, keyword.strip());
            }
            if (status != null) {
                wrapper.eq(Medicine::getStatus, status);
            }
            wrapper.orderByDesc(Medicine::getId);

            List<Medicine> list = medicineDao.selectList(wrapper);
            return new PageInfo<>(list);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public Medicine getById(Long id) {
        return medicineDao.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Medicine medicine) {
        if (medicine == null) {
            throw new IllegalArgumentException("药品信息不能为空");
        }
        sysUserDao.lockActiveAdminIds();
        if (!StringUtils.hasText(medicine.getUnit())) {
            medicine.setUnit("盒");
        }
        if (medicine.getStatus() == null) {
            medicine.setStatus(1);
        }
        normalizeAndValidate(medicine);
        if (nameExists(medicine.getName(), null)) {
            throw new IllegalArgumentException("药品名称已存在");
        }
        medicine.setId(null);
        LocalDateTime now = LocalDateTime.now();
        medicine.setCreateTime(now);
        medicine.setUpdateTime(now);
        try {
            return medicineDao.insert(medicine) > 0;
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("药品名称已存在", exception);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(Medicine medicine) {
        if (medicine == null) {
            throw new IllegalArgumentException("药品信息不能为空");
        }
        requirePositiveId(medicine.getId());
        sysUserDao.lockActiveAdminIds();
        Medicine current = medicineDao.selectByIdForUpdate(medicine.getId());
        if (current == null) {
            throw new IllegalArgumentException("药品不存在");
        }
        applyStockChange(medicine, current);
        normalizeAndValidate(medicine);
        requirePaidStock(medicine.getId(), medicine.getStock(),
                medicine.getStatus() == null ? current.getStatus() : medicine.getStatus());
        if (nameExists(medicine.getName(), medicine.getId())) {
            throw new IllegalArgumentException("药品名称已存在");
        }
        medicine.setCreateTime(null);
        medicine.setUpdateTime(LocalDateTime.now());
        try {
            return medicineDao.updateById(medicine) > 0;
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("药品名称已存在", exception);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteById(Long id) {
        requirePositiveId(id);
        sysUserDao.lockActiveAdminIds();
        Medicine current = medicineDao.selectByIdForUpdate(id);
        if (current == null) {
            throw new IllegalArgumentException("药品不存在");
        }
        if (medicineDao.countPrescriptionItemByMedicineId(id) > 0) {
            throw new IllegalArgumentException("该药品已被处方引用，不能删除");
        }
        return medicineDao.deleteById(id) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateStatus(Long id, Integer status) {
        requirePositiveId(id);
        validateStatus(status);
        sysUserDao.lockActiveAdminIds();
        Medicine current = medicineDao.selectByIdForUpdate(id);
        if (current == null) {
            throw new IllegalArgumentException("药品不存在");
        }
        requirePaidStock(id, current.getStock(), status);
        Medicine medicine = new Medicine();
        medicine.setId(id);
        medicine.setStatus(status);
        medicine.setUpdateTime(LocalDateTime.now());
        return medicineDao.updateById(medicine) > 0;
    }

    @Override
    public List<Medicine> listEnabled(String keyword) {
        LambdaQueryWrapper<Medicine> wrapper = new LambdaQueryWrapper<Medicine>()
                .eq(Medicine::getStatus, 1);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Medicine::getName, keyword.strip());
        }
        wrapper.orderByAsc(Medicine::getName);
        return medicineDao.selectList(wrapper);
    }

    private void requirePaidStock(Long id, Integer stock, Integer status) {
        long reserved = medicineDao.selectPaidQuantitiesForUpdate(id).stream()
                .mapToLong(Integer::longValue).sum();
        if (nativePaymentGuard != null) reserved += nativePaymentGuard.reservedQuantity(id);
        if (stock == null || stock < reserved) {
            throw new IllegalArgumentException("库存不能低于已付款处方预留数量");
        }
        if (reserved > 0 && !Integer.valueOf(1).equals(status)) {
            throw new IllegalArgumentException("该药品已有已付款待取药处方，不能停用");
        }
    }

    private void applyStockChange(Medicine request, Medicine current) {
        int currentStock = current.getStock() == null ? 0 : current.getStock();
        Integer expectedStock = request.getExpectedStock();
        if (expectedStock != null && expectedStock != currentStock) {
            throw new IllegalArgumentException("药品库存已变化，请刷新后重试");
        }
        if (expectedStock == null && request.getStock() != null && request.getStock() != currentStock) {
            throw new IllegalArgumentException("药品库存已变化；如需调整库存，请提交原库存和新的库存值");
        }
        if (request.getStock() == null) {
            request.setStock(currentStock);
        }
    }

    private void normalizeAndValidate(Medicine medicine) {
        medicine.setName(requireTrimmed(medicine.getName(), 100, "药品名称"));
        optionalLength(medicine.getSpec(), 100, "药品规格");
        if (medicine.getUnit() != null) {
            String unit = medicine.getUnit().strip();
            if (unit.isEmpty()) {
                medicine.setUnit(null);
            } else {
                if (textLength(unit) > 20) {
                    throw new IllegalArgumentException("药品单位长度不能超过 20");
                }
                medicine.setUnit(unit);
            }
        }
        optionalLength(medicine.getUsageDesc(), 200, "用法说明");
        validatePrice(medicine.getPrice());
        if (medicine.getStock() == null) {
            throw new IllegalArgumentException("库存不能为空");
        }
        if (medicine.getStock() < 0) {
            throw new IllegalArgumentException("库存不能为负数");
        }
        if (medicine.getStatus() != null) {
            validateStatus(medicine.getStatus());
        }
    }

    private void validatePrice(BigDecimal price) {
        if (price == null) {
            throw new IllegalArgumentException("单价不能为空");
        }
        if (price.signum() < 0) {
            throw new IllegalArgumentException("单价不能为负数");
        }
        if (price.compareTo(MAX_DECIMAL_10_2) > 0) {
            throw new IllegalArgumentException("单价不能超过 99999999.99");
        }
        if (price.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("单价最多保留 2 位小数");
        }
    }

    private void validateStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("状态值只能为 0 或 1");
        }
    }

    private String requireTrimmed(String value, int maxLength, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String trimmed = value.strip();
        if (textLength(trimmed) > maxLength) {
            throw new IllegalArgumentException(label + "长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private void optionalLength(String value, int maxLength, String label) {
        if (value != null && textLength(value) > maxLength) {
            throw new IllegalArgumentException(label + "长度不能超过 " + maxLength);
        }
    }

    private int textLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private void requirePositiveId(Long id) {
        if (id == null || id < 1) {
            throw new IllegalArgumentException("药品id必须为正整数");
        }
    }

    private boolean nameExists(String name, Long excludeId) {
        LambdaQueryWrapper<Medicine> wrapper = new LambdaQueryWrapper<Medicine>()
                .eq(Medicine::getName, name);
        if (excludeId != null) {
            wrapper.ne(Medicine::getId, excludeId);
        }
        return medicineDao.selectCount(wrapper) > 0;
    }
}
