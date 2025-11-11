package com.example.seckillsystem.config; // 确保这是你正确的包名


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scripting.support.ResourceScriptSource;

@Configuration
public class RedisConfig {

    /**
     * RedisTemplate Bean (保持不变)
     * @param connectionFactory Spring Boot 自动配置好的连接工厂
     * @return RedisTemplate 实例
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        // 创建 RedisTemplate 对象
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        // 设置连接工厂
        template.setConnectionFactory(connectionFactory);

        // 创建 JSON 序列化工具
        //GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        // 【新增】创建一个 String 序列化器
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        // 【V5.2 终极版序列化器】
        // 它会智能地调用 .toString()
        // 存 Long 1000 -> 存入 "1000"
        // 存 String "hello" -> 存入 "hello"
        GenericToStringSerializer<Object> toStringSerializer = new GenericToStringSerializer<>(Object.class);

        // Key 的序列化方式为 String (保持不变)
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // 【核心改动】使用 GenericToStringSerializer 作为 Value 序列化器
        template.setValueSerializer(toStringSerializer);
        template.setHashValueSerializer(toStringSerializer);

        template.afterPropertiesSet();
        return template;
    }


    /**
     * 【V4.x 旧版脚本】
     * 我们保留这个 Bean，以防需要回滚
     * Bean 的默认名称是方法名："seckillScript"
     */
    @Bean
    public DefaultRedisScript<Long> seckillScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/seckill.lua")));
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    /**
     * 【V5.1 新增】
     * 加载我们新的“库存分片”Lua 脚本
     * @return
     */
    @Bean("seckillScriptV5") // 【重要】我们为这个 Bean 明确指定一个新名称
    public DefaultRedisScript<Long> seckillScriptV5() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        // 【重要】确保你的新脚本文件叫 "seckill-v5.lua"，并放在 resources/scripts/ 目录下
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/seckill.lua")));
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    /**
     * RabbitMQ 消息转换器 (保持不变)
     * 为 seckill-api (生产者) 也配置 JSON 消息转换器。
     */
}
