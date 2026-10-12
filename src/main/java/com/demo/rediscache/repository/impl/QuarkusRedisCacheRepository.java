package com.demo.rediscache.repository.impl;

import com.demo.rediscache.domain.CacheOperation;
import com.demo.rediscache.exception.CacheConnectionException;
import com.demo.rediscache.exception.CacheException;
import com.demo.rediscache.exception.CacheOperationException;
import com.demo.rediscache.repository.CacheRepository;
import io.quarkus.arc.DefaultBean;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.keys.KeyScanArgs;
import io.quarkus.redis.datasource.keys.RedisKeyNotFoundException;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.vertx.core.VertxException;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * {@link CacheRepository} con el cliente Redis oficial de Quarkus ({@code quarkus-redis-client}, basado en Vert.x).
 * <p>
 * Los valores se guardan tal cual llegan ({@code byte[]}): el codec de {@code byte[]} de Quarkus no les agrega
 * ningun formato propio, por lo que se leen igual desde cualquier cliente de Redis.
 * <p>
 * El cliente se conecta de forma perezosa: si Redis no esta disponible al arrancar, la aplicacion arranca igual
 * y cada operacion falla con {@link CacheConnectionException} hasta que Redis vuelva.
 */
@ApplicationScoped
@DefaultBean
public class QuarkusRedisCacheRepository implements CacheRepository {

    private static final Logger LOG = Logger.getLogger(QuarkusRedisCacheRepository.class);

    private final RedisDataSource ds;
    private final ValueCommands<String, byte[]> values;
    private final KeyCommands<String> keys;
    /** Mismo timeout que usa la API bloqueante de Quarkus ({@code quarkus.redis.timeout}). */
    private final Duration timeout;

    public QuarkusRedisCacheRepository(RedisDataSource ds,
            @ConfigProperty(name = "quarkus.redis.timeout", defaultValue = "10s") Duration timeout) {
        this.ds = ds;
        this.values = ds.value(byte[].class);
        this.keys = ds.key();
        this.timeout = timeout;
    }

    @Override
    public void set(String key, byte[] value, Duration ttl) {
        execute(CacheOperation.SET, key, () -> {
            values.set(key, value, new SetArgs().px(ttl));
            return null;
        });
    }

    @Override
    public boolean setIfAbsent(String key, byte[] value, Duration ttl) {
        // SET key value NX PX ttl (atomico). ValueCommands.set(..., SetArgs) no dice si guardo o no y setnx()
        // no acepta TTL, asi que se envia el comando directo: Redis responde OK si guardo y nil si ya existia.
        return execute(CacheOperation.SET_IF_ABSENT, key, () -> {
            Request request = Request.cmd(Command.SET)
                    .arg(key)
                    .arg(value)
                    .arg("NX")
                    .arg("PX")
                    .arg(ttl.toMillis());
            Response response = ds.getReactive().getRedis().send(request).await().atMost(timeout);
            return response != null;
        });
    }

    @Override
    public Optional<byte[]> get(String key) {
        return execute(CacheOperation.GET, key, () -> Optional.ofNullable(values.get(key)));
    }

    @Override
    public Map<String, byte[]> getAll(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        return execute(CacheOperation.GET_ALL, keys.size() + " llaves", () -> {
            Map<String, byte[]> result = new HashMap<>();
            // MGET devuelve una entrada con valor null para cada llave que no existe
            values.mget(keys.toArray(String[]::new)).forEach((k, v) -> {
                if (v != null) {
                    result.put(k, v);
                }
            });
            return result;
        });
    }

    @Override
    public Set<String> scan(String pattern) {
        return execute(CacheOperation.KEYS, pattern, () -> scanKeys(pattern));
    }

    @Override
    public boolean exists(String key) {
        return execute(CacheOperation.EXISTS, key, () -> keys.exists(key));
    }

    @Override
    public boolean delete(String key) {
        return execute(CacheOperation.DELETE, key, () -> keys.del(key) > 0);
    }

    @Override
    public long deleteByPattern(String pattern) {
        return execute(CacheOperation.DELETE_BY_PATTERN, pattern, () -> {
            Set<String> found = scanKeys(pattern);
            return found.isEmpty() ? 0L : (long) keys.del(found.toArray(String[]::new));
        });
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        return execute(CacheOperation.EXPIRE, key, () -> keys.expire(key, ttl));
    }

    @Override
    public Optional<Duration> ttl(String key) {
        return execute(CacheOperation.TTL, key, () -> {
            try {
                long millis = keys.pttl(key);
                // -1: la llave existe pero no tiene expiracion
                return millis < 0 ? Optional.<Duration>empty() : Optional.of(Duration.ofMillis(millis));
            } catch (RedisKeyNotFoundException e) {
                // A diferencia de Redis (-2), el cliente de Quarkus lanza excepcion si la llave no existe
                return Optional.<Duration>empty();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Recorre el cursor de SCAN completo (no bloquea el servidor como lo haria KEYS). */
    private Set<String> scanKeys(String pattern) {
        Set<String> result = new TreeSet<>();
        keys.scan(new KeyScanArgs().match(pattern)).toIterable().forEach(result::add);
        return result;
    }

    /** Ejecuta la operacion traduciendo cualquier error del cliente a una {@link CacheException}. */
    private <R> R execute(CacheOperation operation, String key, Supplier<R> action) {
        try {
            return action.get();
        } catch (Exception e) {
            LOG.errorf(e, "Fallo la operacion %s sobre '%s'", operation, key);
            throw esFalloDeConexion(e)
                    ? new CacheConnectionException(operation, key, e)
                    : new CacheOperationException(operation, key, e);
        }
    }

    /**
     * Busca en toda la cadena de causas: la API bloqueante envuelve los errores en {@code CompletionException}.
     * Un error de conexion llega como {@code ConnectException}/{@code IOException} o {@code VertxException}
     * (conexion cerrada); un timeout, como {@code TimeoutException} de Mutiny o de java.util.concurrent.
     * Cualquier otra cosa (p. ej. WRONGTYPE) es una respuesta de error de Redis.
     */
    private static boolean esFalloDeConexion(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException
                    || t instanceof VertxException
                    || t instanceof TimeoutException
                    || t instanceof io.smallrye.mutiny.TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
