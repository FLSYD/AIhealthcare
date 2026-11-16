package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.service.LoginService;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.RequireRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import com.aaa.aihealthcare.entity.Login;
import com.aaa.aihealthcare.vo.PasswordChange;
import com.aaa.aihealthcare.entity.SysUser;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@RequireRoles({"ADMIN", "DOCTOR", "USER", "PHARMACIST"})
public class LoginController {

    @Autowired
    private LoginService loginService;

    @PostMapping("/login")
    public Result login(@RequestBody Login login) {
        return loginService.login(
                login.getUsername(),
                login.getPassword()
        );
    }
    
    @GetMapping("/me")
    public Result me(
            @RequestAttribute("loginUserId") Long userId) {

        return loginService.getById(userId);
    }
    
    @PostMapping("/logout")
    public Result logout(
            @RequestAttribute("loginToken") String token) {

        return loginService.logout(token);
    }

    @PutMapping("/profile")
    public Result profile(@RequestAttribute("loginUserId") Long userId, @RequestBody SysUser request) {
        return loginService.updateProfile(userId, request);
    }

    @PutMapping("/password")
    public Result password(@RequestAttribute("loginUserId") Long userId,
            @RequestBody PasswordChange request) {
        return loginService.changePassword(userId, request);
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result avatar(@RequestAttribute("loginUserId") Long userId,
            @RequestParam("file") MultipartFile file) {
        return loginService.uploadAvatar(userId, file);
    }

    @GetMapping({"/avatar/{filename}", "/login/avatar/{filename}"})
    public ResponseEntity<Resource> avatar(@PathVariable String filename) {
        Resource resource = loginService.getAvatar(filename);
        if (resource == null) {
            return ResponseEntity.notFound().build();
        }
        String type = filename.endsWith(".jpg") ? "image/jpeg"
                : filename.endsWith(".png") ? "image/png"
                : filename.endsWith(".gif") ? "image/gif" : "image/webp";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header("X-Content-Type-Options", "nosniff").body(resource);
    }

    @ExceptionHandler(DataAccessException.class)
    public Result dataAccessFailed(DataAccessException exception) {
        return Result.error("账号数据操作失败，请稍后重试");
    }
}
