package com.demo.rediscache.service.impl;

import com.demo.rediscache.config.RedisCacheConfig;
import com.demo.rediscache.repository.CacheRepository;
import com.demo.rediscache.service.CacheService;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Implementacion de {@link CacheService}: valida los argumentos, aplica el prefijo del microservicio y el TTL
 * por defecto, y delega el acceso a Redis en {@link CacheRepository}.
 */
@ApplicationScoped
@DefaultBean
public class CacheServiceImpl implements CacheService {

    private static final Logger LOG = Logger.getLogger(CacheServiceImpl.class);

    private final CacheRepository repository;
    private final RedisCacheConfig config;

    public CacheServiceImpl(CacheRepository repository, RedisCacheConfig config) {
        this.repository = repository;
        this.config = config;
    }

    @Override
    public void set(String key, byte[] value) {
        set(key, value, config.defaultTtl());
    }

    @Override
    public void set(String key, byte[] value, Duration ttl) {
        requireKey(key);
        requireValue(value);
        requireTtl(ttl);
        repository.set(fullKey(key), value, ttl);
        LOG.debugf("SET %s (%d bytes, ttl=%s)", key, value.length, ttl);
    }

    @Override
    public boolean setIfAbsent(String key, byte[] value, Duration ttl) {
        requireKey(key);
        requireValue(value);
        requireTtl(ttl);
        boolean saved = repository.setIfAbsent(fullKey(key), value, ttl);
        LOG.debugf("SETNX %s -> %s", key, saved ? "guardado" : "ya existia");
        return saved;
    }

    @Override
    public Optional<byte[]> get(String key) {
        requireKey(key);
        Optional<byte[]> value = repository.get(fullKey(key));
        LOG.debugf("GET %s -> %s", key, value.map(v -> v.length + " bytes").orElse("no existe"));
        return value;
    }

    @Override
    public Map<String, byte[]> getAll(String pattern) {
        Set<String> keys = keys(pattern);
        List<String> fullKeys = keys.stream().map(this::fullKey).toList();
        Map<String, byte[]> raw = repository.getAll(fullKeys);

        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String key : keys) {
            byte[] value = raw.get(fullKey(key));
            // Pudo expirar entre el SCAN y la lectura
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    @Override
    public Set<String> keys() {
        return keys("*");
    }

    @Override
    public Set<String> keys(String pattern) {
        requirePattern(pattern);
        return repository.scan(fullKey(pattern)).stream()
                .map(this::logicalKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public boolean exists(String key) {
        requireKey(key);
        return repository.exists(fullKey(key));
    }

    @Override
    public boolean delete(String key) {
        requireKey(key);
        boolean deleted = repository.delete(fullKey(key));
        LOG.debugf("DEL %s -> %s", key, deleted ? "eliminada" : "no existia");
        return deleted;
    }

    @Override
    public long deleteByPattern(String pattern) {
        requirePattern(pattern);
        long deleted = repository.deleteByPattern(fullKey(pattern));
        LOG.debugf("DEL %s -> %d llaves", pattern, deleted);
        return deleted;
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        requireKey(key);
        requireTtl(ttl);
        return repository.expire(fullKey(key), ttl);
    }

    @Override
    public Optional<Duration> ttl(String key) {
        requireKey(key);
        return repository.ttl(fullKey(key));
    }

    // ------------------------------------------------------------------ helpers

    private String fullKey(String key) {
        return config.keyPrefix() + key;
    }

    private String logicalKey(String fullKey) {
        String prefix = config.keyPrefix();
        return fullKey.startsWith(prefix) ? fullKey.substring(prefix.length()) : fullKey;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("La llave de cache no puede ser nula ni vacia");
        }
    }

    private static void requirePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("El patron no puede ser nulo ni vacio");
        }
    }

    private static void requireValue(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("No se puede guardar un valor nulo en la cache");
        }
    }

    private static void requireTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("El TTL debe ser mayor a cero");
        }
    }
}
