package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.entity.Medicine;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface MedicineDao extends BaseMapper<Medicine> {

    Medicine selectByIdForUpdate(@Param("id") Long id);

    /** 当前读已付款待发药数量；调用方须先持有管理员锁及对应药品行锁。 */
    List<Integer> selectPaidQuantitiesForUpdate(@Param("medicineId") Long medicineId);

    /**
     * 统计处方明细中引用该药品的记录数，用于删除前的关联校验。
     */
    int countPrescriptionItemByMedicineId(@Param("medicineId") Long medicineId);
}
