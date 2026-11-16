package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.AnonymousAccess;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.service.TriageService;
import com.aaa.aihealthcare.vo.TriageRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequireRoles("USER")
@RequestMapping("/api/triage")
public class TriageController {
    @Autowired
    private TriageService triageService;

    @AnonymousAccess
    @PostMapping("/analyze")
    public Result analyze(@RequestBody TriageRequestVO request) {
        return Result.ok(triageService.analyze(request == null ? null : request.getSymptom()));
    }
}
