package com.intellibase.server.common;

import com.intellibase.server.domain.enums.DocStatusEnum;
import com.intellibase.server.domain.enums.RoleEnum;

/**
 * 全局常量
 */
public final class Constants {

    private Constants() {
    }

    /** 文档解析状态 */
    public static final String DOC_STATUS_PENDING = DocStatusEnum.PENDING.name();
    public static final String DOC_STATUS_PARSING = DocStatusEnum.PARSING.name();
    public static final String DOC_STATUS_EMBEDDING = DocStatusEnum.EMBEDDING.name();
    public static final String DOC_STATUS_COMPLETED = DocStatusEnum.COMPLETED.name();
    public static final String DOC_STATUS_FAILED = DocStatusEnum.FAILED.name();

    /** MQ 队列名 */
    public static final String QUEUE_DOC_PARSE = "doc.parse.queue";
    public static final String QUEUE_DOC_EMBED = "doc.embed.queue";
    public static final String QUEUE_INFERENCE = "inference.queue";

    /** 角色 */
    public static final String ROLE_ADMIN = RoleEnum.ADMIN.name();
    public static final String ROLE_USER = RoleEnum.USER.name();
    public static final String ROLE_VIEWER = RoleEnum.VIEWER.name();

}
