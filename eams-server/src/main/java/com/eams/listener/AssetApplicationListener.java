package com.eams.listener;

import com.eams.constant.ApplicationStatusConstant;
import com.eams.constant.AssetStatusConstant;
import com.eams.dto.AuditEvent;
import com.eams.entity.Asset;
import com.eams.mapper.AssetApplicationMapper;
import com.eams.mapper.AssetMapper;
import com.eams.validation.AssetStatusValidator;
import com.eams.websocket.WebSocketServer;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 审批结果 MQ 消费者：异步处理资产状态更新 + WebSocket 通知
 * 手动 ack + Redis 幂等表 + 业务幂等检查，确保消息不丢不重
 */
@Component
@Slf4j
public class AssetApplicationListener {

    /** Redis 幂等键前缀 */
    private static final String IDEMPOTENT_KEY_PREFIX = "idempotent:event:";
    /** 幂等键 TTL（秒），覆盖消息重试最大存活时间 */
    private static final long IDEMPOTENT_TTL_SECONDS = 3600;

    /** 消息最大重试次数（含首次投递），超过则转入死信队列 */
    private static final long MAX_RETRY_COUNT = 3;

    /** 消息头：投递次数 */
    private static final String HEADER_DELIVERY_COUNT = "x-delivery-count";

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetApplicationMapper assetApplicationMapper;

    @Autowired
    private WebSocketServer webSocketServer;

    @Autowired
    private RedisTemplate redisTemplate;

    @RabbitListener(queues = "asset.application.audit.queue", containerFactory = "rabbitListenerContainerFactory")
    public void handleAuditEvent(AuditEvent event, Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        // 检查重试次数：超过上限则拒绝入死信队列，不 requeue
        Long deliveryCount = message.getMessageProperties().getHeader(HEADER_DELIVERY_COUNT);
        if (deliveryCount != null && deliveryCount >= MAX_RETRY_COUNT) {
            log.warn("消息重试次数已达上限({}), 拒绝并转入死信队列: applicationId={}, eventType={}, deliveryCount={}",
                    MAX_RETRY_COUNT, event.getApplicationId(), event.getEventType(), deliveryCount);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ackErr) {
                log.error("拒绝消息（basicNack requeue=false）失败", ackErr);
            }
            return;
        }

        try {
            log.info("MQ 消费审批事件: applicationId={}, status={}, eventType={}",
                    event.getApplicationId(), event.getStatus(), event.getEventType());

            // Redis 幂等检查：如果已处理过，直接 ack 跳过
            String idempotentKey = IDEMPOTENT_KEY_PREFIX + event.getApplicationId() + ":" + event.getEventType();
            Boolean firstProcess = redisTemplate.opsForValue().setIfAbsent(idempotentKey, "1", IDEMPOTENT_TTL_SECONDS, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(firstProcess)) {
                log.info("Redis 幂等表命中，消息已处理过，跳过: key={}", idempotentKey);
                channel.basicAck(deliveryTag, false);
                return;
            }

            try {
                switch (event.getEventType()) {
                    case "audit" -> handleAudit(event);
                    case "complete" -> handleComplete(event);
                    default -> log.warn("未知事件类型: {}", event.getEventType());
                }

                // 处理成功，手动确认消息
                channel.basicAck(deliveryTag, false);
                log.debug("审批事件已确认: applicationId={}, eventType={}", event.getApplicationId(), event.getEventType());
            } catch (Exception e) {
                // 业务处理失败，删除幂等键，允许重试时重新处理
                redisTemplate.delete(idempotentKey);
                log.error("处理审批事件失败, 将重新入队: applicationId={}, eventType={}, error={}",
                        event.getApplicationId(), event.getEventType(), e.getMessage(), e);
                try {
                    channel.basicNack(deliveryTag, false, true);
                } catch (Exception ackErr) {
                    log.error("basicNack 失败", ackErr);
                }
            }
        } catch (Exception e) {
            log.error("处理审批事件前发生异常（含 Redis 操作失败）: applicationId={}, eventType={}, error={}",
                    event.getApplicationId(), event.getEventType(), e.getMessage(), e);
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (Exception ackErr) {
                log.error("basicNack 失败", ackErr);
            }
        }
    }

    /**
     * 处理审批（通过/拒绝）事件
     * 幂等兜底：通过时检查资产是否已处于已领用状态
     */
    private void handleAudit(AuditEvent event) {
        if (event.getStatus() == ApplicationStatusConstant.APPROVED) {
            Asset asset = assetMapper.getById(event.getAssetId());
            if (asset == null) {
                log.warn("资产不存在，跳过处理: assetId={}", event.getAssetId());
                return;
            }
            if (asset.getStatus() == AssetStatusConstant.USED) {
                log.info("资产已处于已领用状态，跳过重复处理: assetId={}, applicationId={}",
                        event.getAssetId(), event.getApplicationId());
                return;
            }

            // 校验状态流转合法性
            AssetStatusValidator.validate(asset.getStatus(), AssetStatusConstant.USED);

            assetMapper.updateStatus(Asset.builder()
                    .id(event.getAssetId())
                    .status(AssetStatusConstant.USED)
                    .build());
            webSocketServer.sendToAllClient("申请已通过：" + event.getApplicationNo());
        } else if (event.getStatus() == ApplicationStatusConstant.REJECTED) {
            webSocketServer.sendToAllClient("申请已被拒绝：" + event.getApplicationNo());
        }
    }

    /**
     * 处理完成（资产归还）事件
     * 幂等兜底：检查资产是否已处于在库状态
     */
    private void handleComplete(AuditEvent event) {
        Asset asset = assetMapper.getById(event.getAssetId());
        if (asset == null) {
            log.warn("资产不存在，跳过处理: assetId={}", event.getAssetId());
            return;
        }

        if (asset.getStatus() == AssetStatusConstant.IN_STOCK) {
            log.info("资产已处于在库状态，跳过重复处理: assetId={}, applicationId={}",
                    event.getAssetId(), event.getApplicationId());
            return;
        }

        if (asset.getStatus() == AssetStatusConstant.USED) {
            // 校验状态流转合法性
            AssetStatusValidator.validate(asset.getStatus(), AssetStatusConstant.IN_STOCK);

            assetMapper.updateStatus(Asset.builder()
                    .id(asset.getId())
                    .status(AssetStatusConstant.IN_STOCK)
                    .build());
        }
        webSocketServer.sendToAllClient("申请单已完成：" + event.getApplicationNo());
    }
}
