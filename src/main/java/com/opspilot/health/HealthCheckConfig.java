package com.opspilot.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 HealthProbe 的三个依赖探测注册为 actuator 组件（bean 名 *Health 自动映射组件键：
 * redis/qdrant/es）——任何依赖 DOWN 都会让 /actuator/health 聚合变 DOWN，
 * 供 compose healthcheck 与 demo.sh 预检直接消费，无需解析自定义 JSON。
 * 明细（含异常摘要与 chunks 计数）只经鉴权端点 /api/v1/admin/health 暴露。
 */
@Configuration
public class HealthCheckConfig {

    @Bean
    HealthIndicator redisHealth(HealthProbe probe) {
        return () -> toHealth(probe.redis());
    }

    @Bean
    HealthIndicator qdrantHealth(HealthProbe probe) {
        return () -> toHealth(probe.qdrant());
    }

    @Bean
    HealthIndicator elasticsearchClusterHealth(HealthProbe probe) {
        // bean 名 elasticsearchClusterHealth → 组件键 elasticsearchCluster
        return () -> toHealth(probe.es());
    }

    private static Health toHealth(HealthProbe.Comp c) {
        var b = Health.status(c.status().equals("UP") ? "UP" : "DOWN");
        if (!c.detail().isBlank()) b.withDetail("detail", c.detail());
        return b.build();
    }
}
