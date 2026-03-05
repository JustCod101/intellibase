package com.intellibase.server.common;

/**
 * 全局常量
 */
public final class Constants {

    private Constants() {
    }

    /** 文档解析状态 */
    public static final String DOC_STATUS_PENDING = "PENDING";
    public static final String DOC_STATUS_PARSING = "PARSING";
    public static final String DOC_STATUS_EMBEDDING = "EMBEDDING";
    public static final String DOC_STATUS_COMPLETED = "COMPLETED";
    public static final String DOC_STATUS_FAILED = "FAILED";

    /** MQ 队列名 */
    public static final String QUEUE_DOC_PARSE = "doc.parse.queue";
    public static final String QUEUE_DOC_EMBED = "doc.embed.queue";
    public static final String QUEUE_INFERENCE = "inference.queue";

    /** 角色 */
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_VIEWER = "VIEWER";

}
