package com.example.seckillsystem.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // 1. poll interval 每 2 秒（可调）
    @Scheduled(fixedDelay = 2000)
    public void relayPending() {
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT id, payload, attempts FROM outbox WHERE status='PENDING' AND (next_retry_at IS NULL OR next_retry_at <= NOW()) ORDER BY id LIMIT 100"
        );

        for (Map<String, Object> row : pending) {
            Long id = ((Number)row.get("id")).longValue();
            String payload = row.get("payload").toString();
            int attempts = ((Number)row.get("attempts")).intValue();

            try {
                boolean sent = sendWithConfirm(id, payload);
                if (sent) {
                    jdbc.update("UPDATE outbox SET status='SENT', attempts=attempts+1, updated_at = ? WHERE id = ?",
                            Timestamp.from(Instant.now()), id);
                } else {
                    scheduleRetry(id, attempts + 1, "no-ack");
                }
            } catch (Exception ex) {
                log.error("Outbox send exception id={}", id, ex);
                scheduleRetry(id, attempts + 1, ex.getMessage());
            }
        }
    }

    private boolean sendWithConfirm(Long outboxId, String payload) throws Exception {
        // 使用 correlationId = outboxId
        CorrelationData correlationData = new CorrelationData(String.valueOf(outboxId));
        // 这里假设你有 exchange/routingKey 的映射规则；简单示例使用 default exchange -> queue 为 topic
        String exchange = "seckill.exchange";   // 请与 broker 配置匹配
        String routingKey = "seckill.order.created";

        rabbitTemplate.convertAndSend(exchange, routingKey, payload, correlationData);

        // 同步等待确认（for demo）。在高并发生产环境这会有性能问题，但 demo 可接受
        try {
            boolean ok = rabbitTemplate.waitForConfirms(5000);
            if (!ok) {
                log.warn("waitForConfirms returned false for outboxId={}", outboxId);
            }
            return ok;
        } catch (Exception ex) {
            log.error("waitForConfirms exception for outboxId={}", outboxId, ex);
            throw ex;
        }
    }

    private void scheduleRetry(Long id, int attempts, String errorMsg) {
        if (attempts >= 5) {
            // move to dlq
            jdbc.update("INSERT INTO outbox_dlq (outbox_id, payload, error_msg) SELECT id, payload, ? FROM outbox WHERE id = ?", errorMsg, id);
            jdbc.update("UPDATE outbox SET status='FAILED', attempts=?, updated_at=? WHERE id=?", attempts, Timestamp.from(Instant.now()), id);
            log.error("Outbox id={} moved to DLQ after {} attempts.", id, attempts);
        } else {
            // exponential backoff seconds
            long backoffSeconds = Math.min(3600, (long)Math.pow(2, attempts)); // cap 1h
            jdbc.update("UPDATE outbox SET attempts=?, next_retry_at = DATE_ADD(NOW(), INTERVAL ? SECOND) WHERE id=?", attempts, backoffSeconds, id);
            log.warn("Scheduled retry for outbox id={} attempts={} backoff={}s", id, attempts, backoffSeconds);
        }
    }
}
