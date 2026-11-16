package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AvatarValidation;
import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.DoctorProfileService;
import com.aaa.aihealthcare.vo.DoctorAvatarVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class DoctorProfileServiceImpl implements DoctorProfileService {

    private static final String INVALID_DOCTOR_MESSAGE = "账号未关联有效医生，请联系管理员";
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;

    @Autowired
    private DoctorDao doctorDao;

    @Autowired
    private SysUserDao sysUserDao;

    @Value("${healthcare.avatar-dir:uploads/avatars}")
    private String avatarDirectory;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DoctorAvatarVO updateForDoctor(SysUser currentUser, MultipartFile file) {
        Long doctorId = requireBoundDoctorId(currentUser);
        return replaceAvatar(doctorId, file, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DoctorAvatarVO updateByAdmin(Long doctorId, MultipartFile file) {
        if (doctorId == null || doctorId < 1) {
            throw new IllegalArgumentException("医生id必须为正整数");
        }
        return replaceAvatar(doctorId, file, false);
    }

    private DoctorAvatarVO replaceAvatar(Long doctorId, MultipartFile file, boolean forbiddenWhenInvalid) {
        sysUserDao.lockActiveAdminIds();
        Doctor doctor = doctorDao.selectByIdForUpdate(doctorId);
        if (doctor == null || !Integer.valueOf(1).equals(doctor.getStatus())) {
            if (forbiddenWhenInvalid) {
                throw invalidDoctor();
            }
            throw new IllegalArgumentException("医生不存在或已停用");
        }

        PreparedPhoto prepared = preparePhoto(file);
        SavedPhoto saved = writePhoto(prepared);
        try {
            registerRollbackCleanup(saved.path());
            int changed = doctorDao.updateAvatar(doctorId, saved.url(), LocalDateTime.now());
            if (changed != 1) {
                throw new IllegalStateException("医生头像保存失败，请刷新后重试");
            }
            return new DoctorAvatarVO(doctorId, saved.url());
        } catch (RuntimeException exception) {
            deleteGeneratedFile(saved.path());
            throw exception;
        }
    }

    private Long requireBoundDoctorId(SysUser currentUser) {
        if (currentUser == null
                || !"DOCTOR".equals(currentUser.getRole())
                || !Integer.valueOf(1).equals(currentUser.getStatus())
                || !Integer.valueOf(0).equals(currentUser.getDeleted())
                || currentUser.getDoctorId() == null
                || currentUser.getDoctorId() < 1) {
            throw invalidDoctor();
        }
        return currentUser.getDoctorId();
    }

    private PreparedPhoto preparePhoto(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择头像文件");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("头像不能超过2MB");
        }
        try {
            byte[] bytes = file.getBytes();
            String extension = AvatarValidation.extension(bytes);
            if (extension == null) {
                if (!AvatarValidation.supportsWebp()) {
                    throw new IllegalArgumentException(
                            "头像必须是完整有效的JPG、PNG或GIF图片，宽高不超过4096像素；当前环境暂不支持WEBP");
                }
                throw new IllegalArgumentException("头像必须是完整有效的JPG、PNG、GIF、WEBP图片，宽高不超过4096像素");
            }
            return new PreparedPhoto(bytes, extension);
        } catch (IOException exception) {
            throw new IllegalArgumentException("头像文件读取失败，请重新选择", exception);
        }
    }

    private SavedPhoto writePhoto(PreparedPhoto photo) {
        Path saved = null;
        try {
            Path directory = Path.of(avatarDirectory).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + photo.extension();
            saved = directory.resolve(filename);
            Files.write(saved, photo.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return new SavedPhoto(saved, "/api/avatar/" + filename);
        } catch (IOException | RuntimeException exception) {
            if (saved != null) {
                deleteGeneratedFile(saved);
            }
            throw new IllegalStateException("医生头像保存失败，请稍后重试", exception);
        }
    }

    private void registerRollbackCleanup(Path saved) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteGeneratedFile(saved);
                }
            }
        });
    }

    private void deleteGeneratedFile(Path saved) {
        try {
            Files.deleteIfExists(saved);
        } catch (IOException ignored) {
            // 清理失败保留原始业务异常，不能借此删除旧头像。
        }
    }

    private ResponseStatusException invalidDoctor() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, INVALID_DOCTOR_MESSAGE);
    }

    private record PreparedPhoto(byte[] bytes, String extension) {
    }

    private record SavedPhoto(Path path, String url) {
    }
}
