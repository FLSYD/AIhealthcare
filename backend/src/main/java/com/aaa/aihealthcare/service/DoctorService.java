package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.vo.DoctorVO;
import com.aaa.aihealthcare.vo.DoctorCreateResultVO;
import com.github.pagehelper.PageInfo;

public interface DoctorService {

    PageInfo<DoctorVO> page(String keyword, Long departmentId, Integer pageNum, Integer pageSize);

    Doctor getById(Long id);

    /** 保存医生档案；后台新增入口统一使用 createWithAccount 完成开户。 */
    boolean save(Doctor doctor);

    /** 在同一事务中新增医生档案及绑定的登录账号。 */
    DoctorCreateResultVO createWithAccount(Doctor doctor);

    boolean update(Doctor doctor);

    boolean deleteById(Long id);

    boolean updateStatus(Long id, Integer status);
}
