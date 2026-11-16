package com.aaa.aihealthcare.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MiniAuthResponseVO {
    private String token;
    private MiniUserVO user;
}
