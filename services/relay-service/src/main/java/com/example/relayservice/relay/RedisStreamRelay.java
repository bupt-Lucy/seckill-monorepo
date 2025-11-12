package com.example.relayservice.relay;

import com.example.relayservice.config.RelayProperties;
import com.example.relayservice.config.RelayProperties.StreamBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class RedisStreamRelay implements SmartLifecycle, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamRelay.class);

    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final RelayProperties properties;
    private final Map<String, StreamMessageListenerContainer.Subscription> subscriptions = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public RedisStreamRelay(StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
                            StringRedisTemplate redisTemplate,
                            RabbitTemplate rabbitTemplate,
                            RelayProperties properties) {
        this.container = container;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.rabbitTemplate.setMandatory(true);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        properties.getStreams().forEach(this::ensureConsumerGroup);
        properties.getStreams().forEach(binding -> {
            StreamOffset<String> offset = StreamOffset.create(binding.getStreamKey(), ReadOffset.lastConsumed());
            StreamMessageListenerContainer.Subscription subscription = container.receive(
                    Consumer.from(binding.getGroup(), binding.getConsumerName()),
                    offset,
                    message -> handleRecord(binding, message)
            );
            subscriptions.put(binding.identity(), subscription);
            log.info("Subscribed relay consumer {} to stream {} group {}", binding.getConsumerName(), binding.getStreamKey(), binding.getGroup());
        });
        container.start();
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        subscriptions.values().forEach(StreamMessageListenerContainer.Subscription::cancel);
        subscriptions.clear();
        container.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        stop();
    }

    private void handleRecord(StreamBinding binding, MapRecord<String, String, String> record) {
        String payload = record.getValue().get("payload");
        if (!StringUtils.hasText(payload)) {
            log.error("Stream record {} missing payload field", record.getId());
            acknowledge(binding, record);
            return;
        }

        try {
            boolean confirmed = forwardToRabbit(binding, record, payload);
            if (confirmed) {
                acknowledge(binding, record);
            } else {
                scheduleRetry(binding, record, "publisher-nack");
            }
        } catch (Exception ex) {
            log.error("Failed to forward stream record {}", record.getId(), ex);
            scheduleRetry(binding, record, ex.getMessage());
        }
    }

    private boolean forwardToRabbit(StreamBinding binding, MapRecord<String, String, String> record, String payload) throws Exception {
        CorrelationData correlation = new CorrelationData(record.getId().getValue());
        rabbitTemplate.convertAndSend(binding.getExchange(), binding.getRoutingKey(), payload, correlation);
        Duration confirmTimeout = properties.getConfirmTimeout();
        CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        boolean ack = confirm != null && confirm.isAck();
        if (!ack) {
            log.warn("Publisher confirm negative for record {} cause={} ack={}"
                    , record.getId(), confirm != null ? confirm.getReason() : "null", ack);
        }
        return ack;
    }

    private void acknowledge(StreamBinding binding, MapRecord<String, String, String> record) {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        RecordId id = record.getId();
        ops.acknowledge(binding.getStreamKey(), binding.getGroup(), id);
        if (binding.isDeleteAfterAck()) {
            ops.delete(binding.getStreamKey(), id);
        }
        clearAttempts(binding, id);
    }

    private void scheduleRetry(StreamBinding binding, MapRecord<String, String, String> record, String reason) {
        String attemptsKey = resolveAttemptsKey(binding);
        long attempts = redisTemplate.opsForHash().increment(attemptsKey, record.getId().getValue(), 1);
        if (attempts >= binding.getMaxAttempts()) {
            moveToDlq(binding, record, reason, attempts);
        } else {
            log.warn("Relay will retry record {} attempts={} reason={}", record.getId(), attempts, reason);
        }
    }

    private void moveToDlq(StreamBinding binding, MapRecord<String, String, String> record, String reason, long attempts) {
        String dlqKey = resolveDlqKey(binding);
        Map<String, String> payload = new LinkedHashMap<>(record.getValue());
        payload.put("error", Objects.toString(reason, "unknown"));
        payload.put("attempts", Long.toString(attempts));
        payload.put("failedAt", Long.toString(System.currentTimeMillis()));
        redisTemplate.opsForStream().add(dlqKey, payload);
        redisTemplate.opsForStream().acknowledge(binding.getStreamKey(), binding.getGroup(), record.getId());
        if (binding.isDeleteAfterAck()) {
            redisTemplate.opsForStream().delete(binding.getStreamKey(), record.getId());
        }
        clearAttempts(binding, record.getId());
        log.error("Moved stream record {} to DLQ {} after {} attempts", record.getId(), dlqKey, attempts);
    }

    private void clearAttempts(StreamBinding binding, RecordId id) {
        redisTemplate.opsForHash().delete(resolveAttemptsKey(binding), id.getValue());
    }

    private String resolveAttemptsKey(StreamBinding binding) {
        if (StringUtils.hasText(binding.getAttemptsKey())) {
            return binding.getAttemptsKey();
        }
        return binding.getStreamKey() + ":attempts";
    }

    private String resolveDlqKey(StreamBinding binding) {
        if (StringUtils.hasText(binding.getDlqKey())) {
            return binding.getDlqKey();
        }
        return binding.getStreamKey() + ":dlq";
    }

    private void ensureConsumerGroup(StreamBinding binding) {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        try {
            ops.createGroup(binding.getStreamKey(), ReadOffset.latest(), binding.getGroup());
            log.info("Created consumer group {} for stream {}", binding.getGroup(), binding.getStreamKey());
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                log.debug("Consumer group {} already exists for stream {}", binding.getGroup(), binding.getStreamKey());
            } else if (message != null && message.contains("does not exist")) {
                RecordId bootstrapId = ops.add(StreamRecords.newRecord()
                        .in(binding.getStreamKey())
                        .ofMap(Map.of("bootstrap", "1")));
                ops.createGroup(binding.getStreamKey(), ReadOffset.latest(), binding.getGroup());
                ops.delete(binding.getStreamKey(), bootstrapId);
                log.info("Bootstrapped stream {} for group {}", binding.getStreamKey(), binding.getGroup());
            } else {
                throw ex;
            }
        }
    }

    @Scheduled(fixedDelayString = "#{@relayProperties.claimInterval.toMillis()}")
    public void reclaimPending() {
        if (!running) {
            return;
        }
        properties.getStreams().forEach(this::reclaimPendingForBinding);
    }

    private void reclaimPendingForBinding(StreamBinding binding) {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        PendingMessages pending;
        try {
            pending = ops.pending(binding.getStreamKey(), binding.getGroup());
        } catch (Exception ex) {
            log.debug("Failed to inspect pending messages for stream {} group {}: {}", binding.getStreamKey(), binding.getGroup(), ex.getMessage());
            return;
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }
        List<PendingMessage> stale = pending.stream()
                .filter(message -> message.getElapsedTimeSinceLastDelivery().compareTo(properties.getClaimIdle()) > 0)
                .limit(properties.getClaimBatchSize())
                .collect(java.util.stream.Collectors.toList());
        if (stale.isEmpty()) {
            return;
        }
        List<RecordId> ids = stale.stream()
                .map(PendingMessage::getId)
                .collect(java.util.stream.Collectors.toList());
        List<MapRecord<String, String, String>> claimed = ops.claim(binding.getStreamKey(), binding.getGroup(), binding.getConsumerName(),
                properties.getClaimIdle().toMillis(), ids.toArray(new RecordId[0]));
        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        claimed.forEach(record -> handleRecord(binding, record));
    }
}
