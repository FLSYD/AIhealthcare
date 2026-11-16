package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.vo.PasswordChange;
import com.aaa.aihealthcare.entity.SysUser;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface LoginService {

    Result login(String username, String password);

    Result issueSession(SysUser user);

    Result getById(Long id);

    Result logout(String token);

    Result updateProfile(Long userId, SysUser request);

    Result changePassword(Long userId, PasswordChange request);

    Result uploadAvatar(Long userId, MultipartFile file);

    Resource getAvatar(String filename);

}
