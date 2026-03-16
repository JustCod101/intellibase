package com.intellibase.server.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.intellibase.server.domain.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface UserMapper extends BaseMapper<SysUser> {

}
