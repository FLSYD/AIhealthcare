package com.aaa.aihealthcare.vo;

import com.aaa.aihealthcare.entity.SysUser;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MiniUserVO {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private String avatar;
    private BigDecimal balance;
    private Long doctorId;
    private Integer status;
    private String role;
    private Date createTime;
    private Date updateTime;

    public static MiniUserVO from(SysUser user) {
        if (user == null) {
            return null;
        }
        return new MiniUserVO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getPhone(),
                user.getAvatar(),
                user.getBalance(),
                user.getDoctorId(),
                user.getStatus(),
                user.getRole(),
                user.getCreateTime(),
                user.getUpdateTime()
        );
    }
}
