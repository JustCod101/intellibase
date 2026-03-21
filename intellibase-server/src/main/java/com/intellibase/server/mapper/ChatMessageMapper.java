package com.intellibase.server.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.intellibase.server.domain.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

}
