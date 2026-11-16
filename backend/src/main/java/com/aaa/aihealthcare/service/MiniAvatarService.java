package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.vo.MiniAvatarBindRequest;
import org.springframework.web.multipart.MultipartFile;

public interface MiniAvatarService {
    Result upload(Long userId, MultipartFile file, String category);

    Result bind(Long userId, MiniAvatarBindRequest request);
}
