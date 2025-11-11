package com.example.seckillsystem.config; // 确保包名正确

import com.example.seckillsystem.service.SeckillService;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders; // 【V5.4 修正】

import java.util.Map;

/**
 * 【V5.4 核心】定义 RocketMQ 事务监听器
 * 【V5.4 修正】注解不需要参数，它会自动绑定到 properties 中的 "transaction-group"
 */
@RocketMQTransactionListener
public class SeckillTransactionListener implements RocketMQLocalTransactionListener {

    private static final Logger log = LoggerFactory.getLogger(SeckillTransactionListener.class);

    @Autowired
    private SeckillService seckillService; // 注入 SeckillService 来执行 Lua

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 步骤 2：执行本地事务 (我们的 Lua 脚本)
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        log.info("【RocketMQ事务】开始执行本地事务 (Redis Lua)...");

        // 1. 从 arg (我们 send 时传入的) 中解析出参数
        Map<String, Object> argsMap = (Map<String, Object>) arg;
        Long productId = (Long) argsMap.get("productId");
        Long userId = (Long) argsMap.get("userId");
        String threadName = (String) argsMap.get("threadName");

        try {
            // 2. 【核心】调用 V5.1 的 Lua 脚本执行逻辑
            Long luaResult = seckillService.executeLuaScript(productId, userId, threadName);

            if (luaResult == 0) {
                // 3. Lua 脚本成功！
                log.info("【RocketMQ事务】本地事务 (Redis Lua) 成功。准备 COMMIT。");
                return RocketMQLocalTransactionState.COMMIT; // 告诉 RocketMQ "提交"
            } else {
                // 4. Lua 脚本失败 (重复/售罄/桶空)
                log.warn("【RocketMQ事务】本地事务 (Redis Lua) 失败 (Code: {})。准备 ROLLBACK。", luaResult);
                return RocketMQLocalTransactionState.ROLLBACK; // 告诉 RocketMQ "回滚"
            }

        } catch (Exception e) {
            log.error("【RocketMQ事务】本地事务 (Redis Lua) 执行异常。准备 ROLLBACK。", e);
            return RocketMQLocalTransactionState.ROLLBACK; // 异常，也 "回滚"
        }
    }

    /**
     * 步骤 5：事务状态回查
     * 【V5.4 修正】参数类型是 org.springframework.messaging.Message
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {

        // 1. 从“Spring 消息头”中解析出参数
        MessageHeaders headers = msg.getHeaders();
        String userId = headers.get("userId", String.class);
        String productId = headers.get("productId", String.class);

        log.warn("【RocketMQ回查】触发事务状态回查：userId={}, productId={}", userId, productId);

        if (userId == null || productId == null) {
            log.error("【RocketMQ回查】无法从消息头获取 userId 或 productId，事务回滚。");
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        try {
            // 2. 去 Redis 检查“证据”：用户是否在“已购”名单里？
            String userSetKey = SeckillService.USER_SET_KEY_PREFIX + productId;
            boolean isUserInSet = Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(userSetKey, userId)
            );

            if (isUserInSet) {
                // 3. 证据确凿！用户已在名单中，说明本地事务 (Lua) 绝对成功了
                log.warn("【RocketMQ回查】确认事务已成功 (用户在Set中)。 COMMIT。");
                return RocketMQLocalTransactionState.COMMIT;
            } else {
                // 4. 用户不在名单中，说明本地事务失败了
                log.warn("【RocketMQ回查】确认事务已失败 (用户不在Set中)。 ROLLBACK。");
                return RocketMQLocalTransactionState.ROLLBACK;
            }
        } catch (Exception e) {
            log.error("【RocketMQ回查】回查时发生异常！返回 UNKNOWN。", e);
            return RocketMQLocalTransactionState.UNKNOWN; // 稍后再次回查
        }
    }
}