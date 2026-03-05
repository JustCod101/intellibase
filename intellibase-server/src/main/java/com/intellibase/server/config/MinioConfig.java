package com.intellibase.server.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    //endpoint 是 MinIO 仓库的具体地址，它是 OSS 服务对外暴露的网络访问域名。
    @Value("${minio.endpoint}")
    private String endpoint;

    //accessKey 是你的vip会员卡号，用于标识用户身份的唯一标识符。
    @Value("${minio.access-key}")
    private String accessKey;

    //secretKey 配合会员卡号使用的密码。用于加密签名字符串和服务器端验证签名字符串的密钥。
    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

}
