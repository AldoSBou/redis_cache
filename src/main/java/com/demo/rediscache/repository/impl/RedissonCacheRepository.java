package com.demo.rediscache.repository.impl;

import com.demo.rediscache.domain.CacheOperation;
import com.demo.rediscache.exception.CacheConnectionException;
import com.demo.rediscache.exception.CacheException;
import com.demo.rediscache.exception.CacheOperationException;
import com.demo.rediscache.repository.CacheRepository;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;
import org.redisson.client.codec.ByteArrayCodec;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@link CacheRepository} con Redisson.
 * <p>
 * Los valores se guardan tal cual llegan ({@code byte[]}) usando {@link ByteArrayCodec}: Redisson no les agrega
 * ningun formato propio, por lo que se leen igual desde cualquier cliente de Redis.
 */
@ApplicationScoped
@DefaultBean
public class RedissonCacheRepository implements CacheRepository {

    private static final Logger LOG = Logger.getLogger(RedissonCacheRepository.class);

    /**
     * Se resuelve de forma perezosa: si Redis no esta disponible al arrancar, la aplicacion arranca igual;
     * la creacion del cliente falla dentro de {@link #execute} y se reintenta en la siguiente operacion.
     */
    private final Instance<RedissonClient> redisson;

    public RedissonCacheRepository(Instance<RedissonClient> redisson) {
        this.redisson = redisson;
    }

    @Override
    public void set(String key, byte[] value, Duration ttl) {
        execute(CacheOperation.SET, key, () -> {
            bucket(key).set(value, ttl);
            return null;
        });
    }

    @Override
    public boolean setIfAbsent(String key, byte[] value, Duration ttl) {
        return execute(CacheOperation.SET_IF_ABSENT, key, () -> bucket(key).setIfAbsent(value, ttl));
    }

    @Override
    public Optional<byte[]> get(String key) {
        return execute(CacheOperation.GET, key, () -> Optional.ofNullable(bucket(key).get()));
    }

    @Override
    public Map<String, byte[]> getAll(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        return execute(CacheOperation.GET_ALL, keys.size() + " llaves", () -> new HashMap<>(
                client().getBuckets(ByteArrayCodec.INSTANCE).<byte[]>get(keys.toArray(String[]::new))));
    }

    @Override
    public Set<String> scan(String pattern) {
        return execute(CacheOperation.KEYS, pattern, () -> client().getKeys()
                .getKeysStream(KeysScanOptions.defaults().pattern(pattern))
                .collect(Collectors.toCollection(TreeSet::new)));
    }

    @Override
    public boolean exists(String key) {
        return execute(CacheOperation.EXISTS, key, () -> bucket(key).isExists());
    }

    @Override
    public boolean delete(String key) {
        return execute(CacheOperation.DELETE, key, () -> client().getKeys().delete(key) > 0);
    }

    @Override
    public long deleteByPattern(String pattern) {
        return execute(CacheOperation.DELETE_BY_PATTERN, pattern, () -> client().getKeys().deleteByPattern(pattern));
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        return execute(CacheOperation.EXPIRE, key, () -> bucket(key).expire(ttl));
    }

    @Override
    public Optional<Duration> ttl(String key) {
        return execute(CacheOperation.TTL, key, () -> {
            long millis = bucket(key).remainTimeToLive();
            // -2: la llave no existe, -1: la llave no tiene expiracion
            return millis < 0 ? Optional.<Duration>empty() : Optional.of(Duration.ofMillis(millis));
        });
    }

    // ------------------------------------------------------------------ helpers

    private RBucket<byte[]> bucket(String key) {
        return client().getBucket(key, ByteArrayCodec.INSTANCE);
    }

    private RedissonClient client() {
        return redisson.get();
    }

    /** Ejecuta la operacion traduciendo cualquier error de Redisson a una {@link CacheException}. */
    private <R> R execute(CacheOperation operation, String key, Supplier<R> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            LOG.errorf(e, "Fallo la operacion %s sobre '%s'", operation, key);
            throw esFalloDeConexion(e)
                    ? new CacheConnectionException(operation, key, e)
                    : new CacheOperationException(operation, key, e);
        }
    }

    /** Busca en toda la cadena de causas: la creacion perezosa del cliente envuelve el error de conexion. */
    private static boolean esFalloDeConexion(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RedisConnectionException || t instanceof RedisTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
