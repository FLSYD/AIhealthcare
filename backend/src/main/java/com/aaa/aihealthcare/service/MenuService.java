package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.vo.MenuManageRequest;
import com.aaa.aihealthcare.vo.MenuManageVO;
import com.aaa.aihealthcare.vo.MenuVO;

import java.util.List;

public interface MenuService {
    List<MenuVO> listMine(String role);

    List<MenuManageVO> listManage(String keyword);

    Long create(MenuManageRequest request);

    Long update(Long id, MenuManageRequest request);

    int delete(Long id);

    int deleteBatch(List<Long> ids);
}
