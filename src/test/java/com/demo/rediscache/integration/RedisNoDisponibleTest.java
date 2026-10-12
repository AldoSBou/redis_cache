package com.demo.rediscache.integration;

import com.demo.rediscache.domain.CacheOperation;
import com.demo.rediscache.exception.CacheConnectionException;
import com.demo.rediscache.service.CacheService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Contrato con los microservicios: si Redis no esta disponible la aplicacion arranca igual
 * y cada operacion lanza {@link CacheConnectionException}, para que el servicio decida que hacer
 * (p. ej. ir a la base de datos).
 */
@QuarkusTest
@TestProfile(RedisNoDisponibleTest.RedisApagado.class)
class RedisNoDisponibleTest {

    public static class RedisApagado implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Puerto donde no hay ningun Redis escuchando
            return Map.of("quarkus.redis.hosts", "redis://localhost:1");
        }
    }

    @Inject
    CacheService cache;

    @Test
    void getLanzaCacheConnectionException() {
        CacheConnectionException e = assertThrows(CacheConnectionException.class,
                () -> cache.get("cualquiera"));
        assertEquals(CacheOperation.GET, e.getOperation());
        assertEquals("redis-cache-test:cualquiera", e.getKey());
    }

    @Test
    void setLanzaCacheConnectionException() {
        assertThrows(CacheConnectionException.class,
                () -> cache.set("cualquiera", "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void setIfAbsentLanzaCacheConnectionException() {
        assertThrows(CacheConnectionException.class,
                () -> cache.setIfAbsent("cualquiera", "x".getBytes(StandardCharsets.UTF_8),
                        java.time.Duration.ofSeconds(5)));
    }
}
