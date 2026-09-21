package com.demo.rediscache.service;

import com.demo.rediscache.config.RedisCacheConfig;
import com.demo.rediscache.domain.CacheOperation;
import com.demo.rediscache.exception.CacheConnectionException;
import com.demo.rediscache.repository.CacheRepository;
import com.demo.rediscache.service.impl.CacheServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Prueba la capa de servicio aislada: el repositorio (Redis) se mockea. */
class CacheServiceImplTest {

    private static final String PREFIX = "ms:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    private static final String KEY = "pedido:123";
    private static final byte[] VALUE = "valor".getBytes(StandardCharsets.UTF_8);

    private CacheRepository repository;
    private CacheService service;

    @BeforeEach
    void setUp() {
        repository = mock(CacheRepository.class);
        RedisCacheConfig config = mock(RedisCacheConfig.class);
        when(config.keyPrefix()).thenReturn(PREFIX);
        when(config.defaultTtl()).thenReturn(DEFAULT_TTL);
        service = new CacheServiceImpl(repository, config);
    }

    @Test
    void set_aplicaPrefijoYTtlPorDefecto() {
        service.set(KEY, VALUE);

        verify(repository).set("ms:pedido:123", VALUE, DEFAULT_TTL);
    }

    @Test
    void set_conTtlExplicito() {
        service.set(KEY, VALUE, Duration.ofSeconds(30));

        verify(repository).set("ms:pedido:123", VALUE, Duration.ofSeconds(30));
    }

    @Test
    void get_usaLaLlaveCompleta() {
        when(repository.get("ms:pedido:123")).thenReturn(Optional.of(VALUE));

        assertArrayEquals(VALUE, service.get(KEY).orElseThrow());
    }

    @Test
    void keys_devuelveLlavesLogicasSinPrefijo() {
        when(repository.scan("ms:pedido:*")).thenReturn(Set.of("ms:pedido:2", "ms:pedido:1"));

        assertEquals(Set.of("pedido:1", "pedido:2"), service.keys("pedido:*"));
    }

    @Test
    void getAll_mapeaLlavesLogicasYOmiteLasQueExpiraron() {
        when(repository.scan("ms:pedido:*")).thenReturn(Set.of("ms:pedido:1", "ms:pedido:2"));
        when(repository.getAll(List.of("ms:pedido:1", "ms:pedido:2"))).thenReturn(Map.of("ms:pedido:1", VALUE));

        Map<String, byte[]> result = service.getAll("pedido:*");

        assertEquals(Set.of("pedido:1"), result.keySet());
        assertArrayEquals(VALUE, result.get("pedido:1"));
    }

    @Test
    void deleteByPattern_aplicaPrefijoAlPatron() {
        when(repository.deleteByPattern("ms:pedido:*")).thenReturn(3L);

        assertEquals(3L, service.deleteByPattern("pedido:*"));
    }

    @Test
    void erroresDelRepositorio_sePropaganSinCambios() {
        CacheConnectionException caida = new CacheConnectionException(CacheOperation.GET, "ms:pedido:123",
                new RuntimeException("connection refused"));
        when(repository.get("ms:pedido:123")).thenThrow(caida);

        assertSame(caida, assertThrows(CacheConnectionException.class, () -> service.get(KEY)));
    }

    @Test
    void validaArgumentosSinTocarRedis() {
        assertThrows(IllegalArgumentException.class, () -> service.get(null));
        assertThrows(IllegalArgumentException.class, () -> service.get(" "));
        assertThrows(IllegalArgumentException.class, () -> service.set(KEY, null));
        assertThrows(IllegalArgumentException.class, () -> service.set(KEY, VALUE, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> service.set(KEY, VALUE, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> service.expire(KEY, null));
        assertThrows(IllegalArgumentException.class, () -> service.keys(" "));
        assertThrows(IllegalArgumentException.class, () -> service.deleteByPattern(null));
        verifyNoInteractions(repository);
    }

    @Test
    void laLlaveEsLibre_cadaMicroservicioDefineSuFormato() {
        service.set("producto/SKU-001", VALUE);
        service.set("sesion:usuario@mail.com", VALUE);

        verify(repository).set("ms:producto/SKU-001", VALUE, DEFAULT_TTL);
        verify(repository).set("ms:sesion:usuario@mail.com", VALUE, DEFAULT_TTL);
    }

    @Test
    void ttl_delegaEnElRepositorio() {
        when(repository.ttl("ms:pedido:123")).thenReturn(Optional.of(Duration.ofSeconds(42)));

        assertEquals(Duration.ofSeconds(42), service.ttl(KEY).orElseThrow());
        assertTrue(service.ttl("otra").isEmpty());
    }
}
