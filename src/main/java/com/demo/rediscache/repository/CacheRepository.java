package com.demo.rediscache.repository;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Acceso a Redis. Trabaja con llaves completas (ya con el prefijo del microservicio) y no valida argumentos:
 * eso lo hace la capa de servicio.
 * <p>
 * Los errores se lanzan como {@link com.demo.rediscache.exception.CacheConnectionException} (Redis no disponible)
 * o {@link com.demo.rediscache.exception.CacheOperationException} (Redis respondio con error).
 */
public interface CacheRepository {

    void set(String key, byte[] value, Duration ttl);

    boolean setIfAbsent(String key, byte[] value, Duration ttl);

    Optional<byte[]> get(String key);

    /** Valores de las llaves indicadas; las que no existen no aparecen en el resultado. */
    Map<String, byte[]> getAll(Collection<String> keys);

    /** Llaves que coinciden con el patron (glob de Redis), usando SCAN para no bloquear el servidor. */
    Set<String> scan(String pattern);

    boolean exists(String key);

    boolean delete(String key);

    long deleteByPattern(String pattern);

    boolean expire(String key, Duration ttl);

    /** Tiempo de vida restante. Vacio si la llave no existe o no expira. */
    Optional<Duration> ttl(String key);
}
