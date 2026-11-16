package com.aaa.aihealthcare.vo;

import com.aaa.aihealthcare.entity.Doctor;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DoctorVO extends Doctor {
    private String departmentName;
    private String username;

    /** 保留原 username 字段，同时提供参考页面使用的登录账号字段。 */
    public String getLoginUsername() {
        return username;
    }
}
