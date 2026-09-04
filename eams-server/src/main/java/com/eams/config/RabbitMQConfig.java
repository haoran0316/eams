package com.eams.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置：审批业务异步解耦
 */
@Configuration
public class RabbitMQConfig {

    /** 交换机 */
    public static final String ASSET_EXCHANGE = "asset.exchange";

    /** 审批结果队列 */
    public static final String AUDIT_QUEUE = "asset.application.audit.queue";

    /** 路由键 */
    public static final String AUDIT_ROUTING_KEY = "application.audited";

    /** 死信交换机 */
    public static final String ASSET_DLX = "asset.exchange.dlx";

    /** 死信队列 */
    public static final String AUDIT_DLQ = "asset.application.audit.dlq";

    @Bean
    public DirectExchange assetExchange() {
        return new DirectExchange(ASSET_EXCHANGE);
    }

    @Bean
    public Queue auditQueue() {
        return QueueBuilder.durable(AUDIT_QUEUE)
                .deadLetterExchange(ASSET_DLX)
                .deadLetterRoutingKey(AUDIT_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange assetDlx() {
        return new DirectExchange(ASSET_DLX);
    }

    @Bean
    public Queue auditDlq() {
        return new Queue(AUDIT_DLQ, true);
    }

    @Bean
    public Binding auditDlqBinding(DirectExchange assetDlx, Queue auditDlq) {
        return BindingBuilder.bind(auditDlq).to(assetDlx).with(AUDIT_ROUTING_KEY);
    }

    @Bean
    public Binding auditBinding(DirectExchange assetExchange, Queue auditQueue) {
        return BindingBuilder.bind(auditQueue).to(assetExchange).with(AUDIT_ROUTING_KEY);
    }

    /**
     * 手动 ack 模式：确保消息处理成功后才确认，防止丢消息
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        return factory;
    }
}
