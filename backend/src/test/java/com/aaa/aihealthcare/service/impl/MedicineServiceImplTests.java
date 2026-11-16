package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.MedicineDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Medicine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MedicineServiceImplTests {

    @Mock
    private MedicineDao medicineDao;

    @Mock
    private SysUserDao sysUserDao;

    @InjectMocks
    private MedicineServiceImpl service;

    @Test
    void updateRejectsStaleExpectedStockAfterLockingMedicine() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(medicineDao.selectByIdForUpdate(5L)).thenReturn(medicine(5L, 8));
        Medicine request = medicine(5L, 3);
        request.setExpectedStock(7);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.update(request));

        assertEquals("药品库存已变化，请刷新后重试", exception.getMessage());
        InOrder order = inOrder(sysUserDao, medicineDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(medicineDao).selectByIdForUpdate(5L);
        verify(medicineDao, never()).updateById(any(Medicine.class));
    }

    @Test
    void legacyClientCannotOverwriteChangedStockWithoutExpectedStock() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(medicineDao.selectByIdForUpdate(5L)).thenReturn(medicine(5L, 8));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.update(medicine(5L, 3)));

        assertEquals("药品库存已变化；如需调整库存，请提交原库存和新的库存值", exception.getMessage());
    }

    @Test
    void explicitCurrentExpectedStockAllowsIntentionalStockChange() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(medicineDao.selectByIdForUpdate(5L)).thenReturn(medicine(5L, 8));
        when(medicineDao.selectCount(any())).thenReturn(0L);
        Medicine request = medicine(5L, 3);
        request.setExpectedStock(8);
        when(medicineDao.updateById(request)).thenReturn(1);

        assertTrue(service.update(request));

        verify(medicineDao).updateById(request);
    }

    @Test
    void deleteChecksCommittedPrescriptionReferencesAfterMedicineLock() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(medicineDao.selectByIdForUpdate(5L)).thenReturn(medicine(5L, 8));
        when(medicineDao.countPrescriptionItemByMedicineId(5L)).thenReturn(1);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.deleteById(5L));

        assertEquals("该药品已被处方引用，不能删除", exception.getMessage());
        InOrder order = inOrder(sysUserDao, medicineDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(medicineDao).selectByIdForUpdate(5L);
        order.verify(medicineDao).countPrescriptionItemByMedicineId(5L);
        verify(medicineDao, never()).deleteById(5L);
    }

    private Medicine medicine(Long id, Integer stock) {
        Medicine medicine = new Medicine();
        medicine.setId(id);
        medicine.setName("阿莫西林");
        medicine.setSpec("0.25g*24片");
        medicine.setUnit("盒");
        medicine.setPrice(new BigDecimal("12.50"));
        medicine.setStock(stock);
        medicine.setStatus(1);
        return medicine;
    }
}
