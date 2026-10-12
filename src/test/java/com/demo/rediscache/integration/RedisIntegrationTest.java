package com.demo.rediscache.integration;

import com.demo.rediscache.service.CacheService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba todas las capas juntas (service → repository → quarkus-redis-client) contra un Redis real
 * en localhost:6379. Se omite automaticamente si no hay Redis levantado.
 */
@QuarkusTest
@EnabledIf("redisDisponible")
class RedisIntegrationTest {

    @Inject
    CacheService cache;

    static boolean redisDisponible() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 6379), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @AfterEach
    void limpiar() {
        cache.deleteByPattern("test:*");
    }

    @Test
    void setGetExistsDelete() {
        String key = "test:pedido:1";
        byte[] valor = bytes("{\"id\":1,\"nombre\":\"Ana\"}");

        cache.set(key, valor);

        assertTrue(cache.exists(key));
        assertArrayEquals(valor, cache.get(key).orElseThrow());
        assertTrue(cache.delete(key));
        assertFalse(cache.exists(key));
        assertTrue(cache.get(key).isEmpty());
    }

    @Test
    void guardaBytesBinariosSinAlterarlos() {
        byte[] binario = new byte[256];
        for (int i = 0; i < binario.length; i++) {
            binario[i] = (byte) i;
        }

        cache.set("test:binario", binario, Duration.ofSeconds(30));

        assertArrayEquals(binario, cache.get("test:binario").orElseThrow());
    }

    @Test
    void keysGetAllYDeleteByPattern() {
        cache.set("test:num:1", bytes("10"));
        cache.set("test:num:2", bytes("20"));
        cache.set("test:otro", bytes("99"));

        Set<String> nums = Set.of("test:num:1", "test:num:2");
        assertEquals(nums, cache.keys("test:num:*"));

        Map<String, byte[]> todos = cache.getAll("test:num:*");
        assertEquals(nums, todos.keySet());
        assertArrayEquals(bytes("10"), todos.get("test:num:1"));
        assertArrayEquals(bytes("20"), todos.get("test:num:2"));
        assertTrue(cache.keys().containsAll(nums));

        assertEquals(2, cache.deleteByPattern("test:num:*"));
        assertEquals(Set.of("test:otro"), cache.keys("test:*"));
    }

    @Test
    void ttlExpireYSetIfAbsent() {
        String lock = "test:lock";

        assertTrue(cache.setIfAbsent(lock, bytes("a"), Duration.ofSeconds(60)));
        assertFalse(cache.setIfAbsent(lock, bytes("b"), Duration.ofSeconds(60)));
        assertArrayEquals(bytes("a"), cache.get(lock).orElseThrow());

        long ttl = cache.ttl(lock).orElseThrow().toSeconds();
        assertTrue(ttl > 0 && ttl <= 60);

        assertTrue(cache.expire(lock, Duration.ofHours(1)));
        assertTrue(cache.ttl(lock).orElseThrow().toMinutes() > 50);
        assertTrue(cache.ttl("test:no-existe").isEmpty());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
