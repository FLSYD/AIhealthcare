package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AvatarValidation;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.dao.MiniAuthDao;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.LoginService;
import com.aaa.aihealthcare.service.MiniAvatarService;
import com.aaa.aihealthcare.vo.MiniAvatarBindRequest;
import com.aaa.aihealthcare.vo.MiniAvatarUploadVO;
import com.aaa.aihealthcare.vo.MiniUserVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.UUID;

@Service
public class MiniAvatarServiceImpl implements MiniAvatarService {
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private static final Duration UPLOAD_TTL = Duration.ofMinutes(30);
    private static final String UPLOAD_PREFIX = "mini:upload:";
    private static final String AVATAR_PATH_PREFIX = "/api/avatar/";
    private static final String FILENAME_PATTERN = "[a-f0-9]{32}\\.(jpg|png|gif|webp)";

    private final StringRedisTemplate stringRedisTemplate;
    private final MiniAuthDao miniAuthDao;
    private final LoginService loginService;
    private final AvatarWriteExecutor avatarWriter;

    @Value("${healthcare.avatar-dir:uploads/avatars}")
    private String avatarDirectory;

    public MiniAvatarServiceImpl(StringRedisTemplate stringRedisTemplate,
            MiniAuthDao miniAuthDao,
            LoginService loginService,
            AvatarWriteExecutor avatarWriter) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.miniAuthDao = miniAuthDao;
        this.loginService = loginService;
        this.avatarWriter = avatarWriter;
    }

    @Override
    public Result upload(Long userId, MultipartFile file, String category) {
        synchronized (com.aaa.aihealthcare.common.AvatarStorage.LOCK) {
            return uploadProtected(userId, file, category);
        }
    }

    private Result uploadProtected(Long userId, MultipartFile file, String category) {
        if (!"avatar".equals(category)) {
            return Result.error("上传分类必须为avatar");
        }
        if (userId == null || userId < 1) {
            return Result.error("当前账号无效");
        }
        byte[] bytes;
        String extension;
        try {
            if (file == null || file.isEmpty()) {
                return Result.error("请选择头像文件");
            }
            if (file.getSize() > MAX_AVATAR_BYTES) {
                return Result.error("头像不能超过2MB");
            }
            bytes = file.getBytes();
            extension = AvatarValidation.extension(bytes);
        } catch (IOException exception) {
            return Result.error("头像文件读取失败，请重新选择");
        }
        if (extension == null) {
            if (!AvatarValidation.supportsWebp()) {
                return Result.error("头像必须是完整有效的JPG、PNG或GIF图片，宽高不超过4096像素；当前环境暂不支持WEBP");
            }
            return Result.error("头像必须是完整有效的JPG、PNG、GIF、WEBP图片，宽高不超过4096像素");
        }

        Path saved = null;
        boolean created = false;
        try {
            Path directory = Path.of(avatarDirectory).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + extension;
            saved = directory.resolve(filename);
            Files.write(saved, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            created = true;
            Boolean mapped = stringRedisTemplate.opsForValue().setIfAbsent(
                    UPLOAD_PREFIX + filename, String.valueOf(userId), UPLOAD_TTL);
            if (!Boolean.TRUE.equals(mapped)) {
                deleteFile(saved);
                return Result.error("头像上传状态保存失败，请重试");
            }
            return Result.ok(new MiniAvatarUploadVO(AVATAR_PATH_PREFIX + filename));
        } catch (IOException | RuntimeException exception) {
            if (created) {
                deleteFile(saved);
            }
            return Result.error("头像上传失败，请稍后重试");
        }
    }

    @Override
    public Result bind(Long userId, MiniAvatarBindRequest request) {
        if (userId == null || userId < 1 || request == null || request.getPath() == null) {
            return Result.error("头像路径不能为空");
        }
        String filename = filenameFromPath(request.getPath());
        if (filename == null) {
            return Result.error("头像路径无效");
        }
        Result bound = avatarWriter.write(() -> bindProtected(userId, filename), () -> { },
                "头像绑定失败，请稍后重试");
        if (bound.getCode() != 200) {
            return bound;
        }

        Result current = loginService.getById(userId);
        if (current.getCode() != 200 || !(current.getData() instanceof SysUser user)) {
            return current;
        }
        return Result.ok(MiniUserVO.from(user));
    }

    private Result bindProtected(Long userId, String filename) {
        try {
            String ownerId = stringRedisTemplate.opsForValue().get(UPLOAD_PREFIX + filename);
            if (!String.valueOf(userId).equals(ownerId)) {
                return Result.error("头像不存在、已过期或不属于当前账号");
            }
            int changed = miniAuthDao.updateAvatarForActiveUser(userId, AVATAR_PATH_PREFIX + filename);
            if (changed != 1) {
                return Result.error("头像绑定失败，请确认账号状态");
            }
            return Result.ok();
        } catch (RuntimeException exception) {
            return Result.error("头像绑定失败，请稍后重试");
        }
    }

    private String filenameFromPath(String path) {
        if (!path.startsWith(AVATAR_PATH_PREFIX)) {
            return null;
        }
        String filename = path.substring(AVATAR_PATH_PREFIX.length());
        return filename.matches(FILENAME_PATTERN) ? filename : null;
    }

    private void deleteFile(Path saved) {
        if (saved == null) {
            return;
        }
        try {
            Files.deleteIfExists(saved);
        } catch (IOException ignored) {
            // 清理失败不能覆盖原始上传结果。
        }
    }
}
