package com.intellibase.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
/**
 * Redis 全局配置类
 * 核心目的：替换 Spring Data Redis 默认的 JDK 序列化器（JdkSerializationRedisSerializer），
 * 避免存入 Redis 的 Key 和 Value 变成人类无法阅读的十六进制乱码（如 \xac\xed\x00\x05t...）。
 */
@Configuration // 标明这是一个配置类，Spring 启动时会自动扫描并解析它
public class RedisConfig {

    /**
     * 配置并向 Spring 容器注入自定义的 RedisTemplate
     * * @param connectionFactory Redis 连接工厂（Spring Boot 会根据 application.yml 自动注入 Lettuce 或 Jedis 的连接工厂）
     * @return 经过定制化序列器配置的 RedisTemplate 实例
     */
    @Bean // 告诉 Spring：“请把这个方法返回的对象当作一个 Bean 放到容器里，以后谁要用 RedisTemplate 就给谁这个”
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {

        // 1. 实例化一个 RedisTemplate 对象。
        // 泛型定义为 <String, Object>，因为通常我们的 Key 都是字符串，而 Value 可能是各种复杂的 Java 对象或集合。
        RedisTemplate<String, Object> template = new RedisTemplate<>();

        // 2. 绑定连接工厂，让 Template 具备连接 Redis 服务器的能力
        template.setConnectionFactory(connectionFactory);

        // ==================== 核心：序列化器配置 ====================

        // 3. 配置普通 Key 的序列化器
        // 使用 StringRedisSerializer，把 Key 作为纯文本字符串存储。
        // 作用：让你在 Another Redis Desktop Manager 等可视化工具中能清晰地看到 Key 的名字。
        template.setKeySerializer(new StringRedisSerializer());

        // 4. 配置普通 Value 的序列化器
        // 使用 GenericJackson2JsonRedisSerializer，把 Java 对象转换成 JSON 字符串存储。
        // 特点：它不仅存 JSON 数据，还会在 JSON 中隐式加上一个 "@class" 属性（记录该对象的 Java 全类名）。
        // 作用：将来从 Redis 读取数据时，Spring 能根据这个全类名，自动把 JSON 反序列化回原本的 Java 对象，极其方便。
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());

        // 5. 配置 Hash 数据结构中 HashKey 的序列化器
        // 针对类似 Map<String, Object> 的结构，Map 里面的 Key 同样使用字符串序列化
        template.setHashKeySerializer(new StringRedisSerializer());

        // 6. 配置 Hash 数据结构中 HashValue 的序列化器
        // Map 里面的 Value 同样使用 JSON 序列化
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        // ============================================================

        // 7. 触发初始化方法。
        // 确保刚刚 set 进去的那些自定义序列化器参数正确生效（这是 Spring 框架对象初始化的标准动作）
        template.afterPropertiesSet();

        // 8. 返回这个“装修好”的 RedisTemplate
        return template;
    }

}
