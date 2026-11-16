package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.entity.SysMenu;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MenuDao extends BaseMapper<SysMenu> {
    List<SysMenu> selectAll();

    SysMenu selectByIdForUpdate(@Param("id") Long id);

    List<SysMenu> selectAllForUpdate();

    List<SysMenu> selectByIdsForUpdate(@Param("ids") List<Long> ids);
}
