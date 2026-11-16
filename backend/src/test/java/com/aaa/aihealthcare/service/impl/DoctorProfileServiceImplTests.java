package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.vo.DoctorAvatarVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorProfileServiceImplTests {

    @Mock
    private DoctorDao doctorDao;

    @Mock
    private SysUserDao sysUserDao;

    @InjectMocks
    private DoctorProfileServiceImpl service;

    @TempDir
    Path temporaryDirectory;

    @Test
    void doctorAvatarUsesLoginBindingAndLocksAdminThenDoctor() throws Exception {
        ReflectionTestUtils.setField(service, "avatarDirectory", temporaryDirectory.toString());
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(doctorDao.selectByIdForUpdate(8L)).thenReturn(activeDoctor(8L));
        when(doctorDao.updateAvatar(eq(8L), any(String.class), any(LocalDateTime.class))).thenReturn(1);

        DoctorAvatarVO result = service.updateForDoctor(doctorUser(8L), validPng());

        InOrder order = inOrder(sysUserDao, doctorDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(doctorDao).selectByIdForUpdate(8L);
        order.verify(doctorDao).updateAvatar(eq(8L), eq(result.getAvatar()), any(LocalDateTime.class));
        assertTrue(result.getAvatar().matches("/api/avatar/[a-f0-9]{32}\\.png"));
        String filename = result.getAvatar().substring("/api/avatar/".length());
        assertTrue(Files.exists(temporaryDirectory.resolve(filename)));
    }

    @Test
    void databaseFailureDeletesOnlyCurrentFileAndKeepsOldAvatar() throws Exception {
        ReflectionTestUtils.setField(service, "avatarDirectory", temporaryDirectory.toString());
        Path oldAvatar = temporaryDirectory.resolve("old.png");
        Files.write(oldAvatar, validPng().getBytes());
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        Doctor doctor = activeDoctor(8L);
        doctor.setAvatar("/api/avatar/old.png");
        when(doctorDao.selectByIdForUpdate(8L)).thenReturn(doctor);
        when(doctorDao.updateAvatar(eq(8L), any(String.class), any(LocalDateTime.class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThrows(RuntimeException.class,
                () -> service.updateForDoctor(doctorUser(8L), validPng()));

        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(oldAvatar), files.toList());
        }
        assertTrue(Files.exists(oldAvatar));
    }

    @Test
    void invalidImageDoesNotReachDatabaseUpdateOrCreateFile() throws Exception {
        ReflectionTestUtils.setField(service, "avatarDirectory", temporaryDirectory.toString());
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(doctorDao.selectByIdForUpdate(8L)).thenReturn(activeDoctor(8L));
        MockMultipartFile invalid = new MockMultipartFile(
                "file", "fake.png", "image/png", "not-an-image".getBytes());

        assertThrows(IllegalArgumentException.class,
                () -> service.updateForDoctor(doctorUser(8L), invalid));

        verify(doctorDao, never()).updateAvatar(any(), any(), any());
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(), files.toList());
        }
    }

    @Test
    void unboundOrDisabledDoctorCannotUploadAvatar() throws Exception {
        assertThrows(ResponseStatusException.class,
                () -> service.updateForDoctor(doctorUser(null), validPng()));

        SysUser disabled = doctorUser(8L);
        disabled.setStatus(0);
        assertThrows(ResponseStatusException.class,
                () -> service.updateForDoctor(disabled, validPng()));
        verify(doctorDao, never()).selectByIdForUpdate(any());
    }

    private SysUser doctorUser(Long doctorId) {
        SysUser user = new SysUser();
        user.setRole("DOCTOR");
        user.setStatus(1);
        user.setDeleted(0);
        user.setDoctorId(doctorId);
        return user;
    }

    private Doctor activeDoctor(Long id) {
        Doctor doctor = new Doctor();
        doctor.setId(id);
        doctor.setStatus(1);
        return doctor;
    }

    private MockMultipartFile validPng() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return new MockMultipartFile("file", "doctor.png", "image/png", output.toByteArray());
    }
}
