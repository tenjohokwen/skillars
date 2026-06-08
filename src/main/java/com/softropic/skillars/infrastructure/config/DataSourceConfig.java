package com.softropic.skillars.infrastructure.config;


import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.ttddyy.dsproxy.listener.logging.SLF4JLogLevel;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
public class DataSourceConfig {

    // Skipped when datasource.container=true (tests/dev) — Boot auto-configures from @ServiceConnection instead.
    @Bean
    @ConditionalOnProperty(name = "datasource.container", havingValue = "false", matchIfMissing = true)
    DataSource dataSource(HikariConfig hikariConfig) {
        return new HikariDataSource(hikariConfig);
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @ConditionalOnProperty(name = "datasource.container", havingValue = "false", matchIfMissing = true)
    HikariConfig hikariConfig(DataSourceProperties dataSourceProperties) {
        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPassword(dataSourceProperties.getPassword());
        hikariConfig.setUsername(dataSourceProperties.getUsername());
        hikariConfig.setJdbcUrl(dataSourceProperties.getUrl());
        return hikariConfig;
    }

    // Wraps the primary dataSource bean with a SQL-logging proxy when log.database.spy=true.
    // Works regardless of whether the DataSource came from DataSourceConfig or Boot's @ServiceConnection auto-config.
    @Bean
    @ConditionalOnProperty(name = "log.database.spy", havingValue = "true")
    static BeanPostProcessor dataSourceSpyPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if ("dataSource".equals(beanName) && bean instanceof DataSource ds) {
                    return ProxyDataSourceBuilder.create(ds)
                        .name("skillars-spy")
                        .logQueryBySlf4j(SLF4JLogLevel.DEBUG)
                        .build();
                }
                return bean;
            }
        };
    }
}
