package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.AnonymousAccess;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.service.MiniAuthService;
import com.aaa.aihealthcare.service.MiniAvatarService;
import com.aaa.aihealthcare.vo.MiniAvatarBindRequest;
import com.aaa.aihealthcare.vo.MiniLoginRequest;
import com.aaa.aihealthcare.vo.WxLoginRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@RequireRoles({"ADMIN", "DOCTOR", "USER", "PHARMACIST"})
public class MiniAuthController {
    private final MiniAuthService miniAuthService;
    private final MiniAvatarService miniAvatarService;

    public MiniAuthController(MiniAuthService miniAuthService, MiniAvatarService miniAvatarService) {
        this.miniAuthService = miniAuthService;
        this.miniAvatarService = miniAvatarService;
    }

    @AnonymousAccess
    @PostMapping("/auth/login")
    public Result login(@RequestBody MiniLoginRequest request) {
        return miniAuthService.login(request);
    }

    @AnonymousAccess
    @PostMapping("/auth/wx-login")
    public Result wxLogin(@RequestBody WxLoginRequest request) {
        return miniAuthService.wxLogin(request);
    }

    @GetMapping("/auth/me")
    public Result me(@RequestAttribute("loginUserId") Long userId) {
        return miniAuthService.getCurrentUser(userId);
    }

    @PostMapping(value = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result upload(@RequestAttribute("loginUserId") Long userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false) String category) {
        return miniAvatarService.upload(userId, file, category);
    }

    @PutMapping("/profile/avatar")
    public Result avatar(@RequestAttribute("loginUserId") Long userId,
            @RequestBody MiniAvatarBindRequest request) {
        return miniAvatarService.bind(userId, request);
    }

    @ExceptionHandler(DataAccessException.class)
    public Result dataAccessFailed(DataAccessException exception) {
        return Result.error("账号数据操作失败，请稍后重试");
    }
}
