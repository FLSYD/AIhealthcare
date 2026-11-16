package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.vo.TriageResponseVO;

public interface TriageService {
    TriageResponseVO analyze(String symptom);
}
