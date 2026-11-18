package com.havita.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Broker STOMP en /ws. No tiene publicadores ni consumidores en el backend ni en el frontend y no aplica
 * autorizacion por usuario, comunidad ni destino, por lo que queda deshabilitado por defecto (SEC-6).
 * Para reactivarlo hay que poner havita.websocket.enabled=true y, antes, anadir un ChannelInterceptor que
 * autentique la conexion y autorice cada SUBSCRIBE/SEND por comunidad y destinatario.
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "havita.websocket.enabled", havingValue = "true")
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Prefijo para suscripciones de clientes a tópicos de difusión
        config.enableSimpleBroker("/topic", "/queue");
        // Prefijo para mensajes dirigidos a controladores con @MessageMapping
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint WebSocket con soporte y fallback SockJS
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
