package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.entity.Medicine;
import com.aaa.aihealthcare.service.MedicineService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 药品管理接口，统一前缀 /api/medicine。
 */
@RestController
@RequireRoles("ADMIN")
@RequestMapping("/api/medicine")
public class MedicineController {

    @Autowired
    private MedicineService medicineService;

    @GetMapping("/page")
    @RequireRoles({"ADMIN", "PHARMACIST"})
    public Result page(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer status,
                       @RequestParam(defaultValue = "1") Integer pageNum,
                       @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<Medicine> pageInfo = medicineService.page(keyword, status, pageNum, pageSize);
        return Result.ok(pageInfo);
    }

    /**
     * 下拉/开方用药品列表，仅返回启用状态。
     */
    @GetMapping("/list")
    @RequireRoles({"ADMIN", "DOCTOR"})
    public Result list(@RequestParam(required = false) String keyword) {
        List<Medicine> list = medicineService.listEnabled(keyword);
        return Result.ok(list);
    }

    @GetMapping("/{id}")
    @RequireRoles({"ADMIN", "PHARMACIST"})
    public Result getById(@PathVariable Long id) {
        Medicine medicine = medicineService.getById(id);
        if (medicine == null) {
            return Result.error("药品不存在");
        }
        return Result.ok(medicine);
    }

    @PostMapping
    @OperationLog(module = "药品管理", action = "新增药品")
    public Result save(@RequestBody Medicine medicine) {
        try {
            return medicineService.save(medicine) ? Result.ok() : Result.error("新增失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping
    @OperationLog(module = "药品管理", action = "修改药品")
    public Result update(@RequestBody Medicine medicine) {
        try {
            return medicineService.update(medicine) ? Result.ok() : Result.error("修改失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @OperationLog(module = "药品管理", action = "删除药品")
    public Result deleteById(@PathVariable Long id) {
        try {
            return medicineService.deleteById(id) ? Result.ok() : Result.error("删除失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/{id}/status/{status}")
    @OperationLog(module = "药品管理", action = "修改药品状态")
    public Result updateStatus(@PathVariable Long id, @PathVariable Integer status) {
        try {
            return medicineService.updateStatus(id, status) ? Result.ok() : Result.error("状态修改失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}