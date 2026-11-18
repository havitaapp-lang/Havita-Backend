package com.havita.config;

import com.havita.modules.organization.infrastructure.TenantRepository;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestConfig {
    @Bean
    @Primary
    public TenantRepository tenantRepository() {
        return Mockito.mock(TenantRepository.class);
    }
}
