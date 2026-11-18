package com.havita.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables Spring's @Async processing for asynchronous tasks like push notification delivery.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
