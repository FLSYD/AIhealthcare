package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.controller.*;
import com.aaa.aihealthcare.dao.LoginDao;
import com.aaa.aihealthcare.dao.PharmacyDao;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.interceptor.LoginInterceptor;
import com.aaa.aihealthcare.service.impl.PharmacyServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** 登录凭证和账号读取使用真实服务/MySQL，Redis使用验证设施内存替身；不启动真实HTTP服务。 */
class PharmacistRoleAccessVerificationIT extends MysqlVerificationSupport {
    @Autowired LoginDao loginDao;
    @Autowired PharmacyDao pharmacyDao;
    @Autowired StringRedisTemplate redis;

    private String token() {
        var result=login.login("synthetic_3","Synthetic-Test-Only-Password");
        assertEquals(200,result.getCode()); return (String)result.getData();
    }

    private MockHttpServletResponse authorize(Object controller, String method, String token, boolean allowed) throws Exception {
        var interceptor=new LoginInterceptor();
        ReflectionTestUtils.setField(interceptor,"loginDao",loginDao);
        ReflectionTestUtils.setField(interceptor,"stringRedisTemplate",redis);
        ReflectionTestUtils.setField(interceptor,"objectMapper",new ObjectMapper());
        var request=new MockHttpServletRequest("GET","/api/verification");
        request.addHeader("Authorization","Bearer "+token);
        var response=new MockHttpServletResponse();
        var handler=new HandlerMethod(controller,Arrays.stream(controller.getClass().getMethods())
                .filter(m->m.getName().equals(method)).findFirst().orElseThrow());
        assertEquals(allowed,interceptor.preHandle(request,response,handler),controller.getClass().getSimpleName()+"."+method);
        if (allowed) assertEquals(3L,request.getAttribute("loginUserId"));
        return response;
    }

    @Test void pharmacistCanAccessNotificationsProfilePasswordMenusAndReadOnlyMedicine() throws Exception {
        String token=token();
        for(String method: new String[]{"page","unreadCount","markRead","markAllRead"})
            authorize(new NotificationController(),method,token,true);
        for(String method: new String[]{"me","profile","password","logout"})
            authorize(new LoginController(),method,token,true);
        authorize(new PermissionController(),"permissions",token,true);
        authorize(new MenuController(),"menus",token,true);
        authorize(new MedicineController(),"page",token,true);
        authorize(new MedicineController(),"getById",token,true);
        var pharmacy=new PharmacyController(new PharmacyServiceImpl(pharmacyDao),consultation);
        for(String method: new String[]{"prescriptions","detail","dispense"}) authorize(pharmacy,method,token,true);
    }

    @Test void pharmacistDoesNotInheritAdminMedicineWritesOrDoctorConsultation() throws Exception {
        String token=token();
        for(String method: new String[]{"save","update","deleteById","updateStatus"})
            assertEquals(403,authorize(new MedicineController(),method,token,false).getStatus());
        assertEquals(403,authorize(new DoctorWorkspaceController(),"overview",token,false).getStatus());
        assertEquals(403,authorize(new SysUserController(),"list",token,false).getStatus());
    }

    @Test void disablingOrDeletingPharmacistRejectsExistingSession() throws Exception {
        String token=token();
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=3");
        assertEquals(401,authorize(new NotificationController(),"unreadCount",token,false).getStatus());
        jdbc.update("UPDATE sys_user SET status=1 WHERE id=3");
        token=token(); jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=3");
        assertEquals(401,authorize(new NotificationController(),"unreadCount",token,false).getStatus());
    }

    @Test void personalProfileUpdateCannotChangePharmacistRoleOrBinding() {
        SysUser request=new SysUser(); request.setNickname("Synthetic pharmacist updated");
        request.setRole("ADMIN"); request.setDoctorId(1L); request.setStatus(0);
        assertEquals(200,login.updateProfile(3L,request).getCode());
        var actual=loginDao.getById(3L);
        assertEquals("PHARMACIST",actual.getRole()); assertEquals(2L,actual.getDoctorId());
        assertEquals(1,actual.getStatus()); assertEquals("Synthetic pharmacist updated",actual.getNickname());
    }
}
