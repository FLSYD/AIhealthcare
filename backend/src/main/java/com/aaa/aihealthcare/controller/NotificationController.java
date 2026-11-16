package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.service.NotificationService;
import com.aaa.aihealthcare.vo.MiniPageVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 仅允许读取和标记当前账号的消息，接收人由服务端登录态决定。 */
@RestController
@RequestMapping("/api/notifications")
@RequireRoles({"ADMIN", "DOCTOR", "USER", "PHARMACIST"})
public class NotificationController {
    @Autowired
    private NotificationService notificationService;

    @GetMapping
    public Result page(@RequestAttribute("loginUserId") Long userId,
                       @RequestParam(defaultValue = "false") boolean unreadOnly,
                       @RequestParam(required = false) Integer pageNum,
                       @RequestParam(required = false) Integer pageSize,
                       @RequestParam(required = false) Integer current,
                       @RequestParam(required = false) Integer size) {
        var page = notificationService.page(userId, unreadOnly,
                current != null ? current : pageNum, size != null ? size : pageSize);
        return Result.ok(current != null || size != null ? MiniPageVO.from(page) : page);
    }

    @GetMapping("/unread-count")
    public Result unreadCount(@RequestAttribute("loginUserId") Long userId) {
        return Result.ok(notificationService.unreadCount(userId));
    }

    @PutMapping("/{id}/read")
    public Result markRead(@RequestAttribute("loginUserId") Long userId, @PathVariable Long id) {
        notificationService.markRead(userId, id);
        return Result.ok();
    }

    @PutMapping("/read-all")
    public Result markAllRead(@RequestAttribute("loginUserId") Long userId) {
        return Result.ok(notificationService.markAllRead(userId));
    }
}
