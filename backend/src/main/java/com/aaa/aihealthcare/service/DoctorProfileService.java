package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.vo.DoctorAvatarVO;
import org.springframework.web.multipart.MultipartFile;

public interface DoctorProfileService {

    DoctorAvatarVO updateForDoctor(SysUser currentUser, MultipartFile file);

    DoctorAvatarVO updateByAdmin(Long doctorId, MultipartFile file);
}
