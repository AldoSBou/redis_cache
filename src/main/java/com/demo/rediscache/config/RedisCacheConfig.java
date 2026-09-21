package com.demo.rediscache.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

/**
 * Configuracion de la libreria (prefijo {@code redis-cache}).
 * <p>
 * La conexion a Redis se configura aparte, con las propiedades {@code quarkus.redisson.*}.
 */
@ConfigMapping(prefix = "redis-cache")
public interface RedisCacheConfig {

    /**
     * Prefijo aplicado a todas las llaves para aislar cada microservicio en un Redis compartido
     * (p. ej. {@code pedidos-ms:}). Es obligatorio: sin el, dos microservicios podrian pisarse las llaves.
     */
    String keyPrefix();

    /** TTL usado cuando no se indica uno explicito. */
    @WithDefault("10m")
    Duration defaultTtl();
}
