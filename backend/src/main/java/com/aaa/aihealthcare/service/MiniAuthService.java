package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.vo.MiniLoginRequest;
import com.aaa.aihealthcare.vo.WxLoginRequest;

public interface MiniAuthService {
    Result login(MiniLoginRequest request);

    Result wxLogin(WxLoginRequest request);

    Result getCurrentUser(Long userId);
}
