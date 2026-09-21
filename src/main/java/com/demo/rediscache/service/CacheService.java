package com.demo.rediscache.service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * API de cache para los microservicios.
 * <ul>
 *     <li>Las llaves son logicas y su formato lo define cada microservicio (p. ej. {@code pedido:123}).
 *     El prefijo {@code redis-cache.key-prefix} se aplica internamente y nunca se ve desde fuera.</li>
 *     <li>Los valores son {@code byte[]}: la serializacion es responsabilidad de quien usa la cache
 *     (ver {@link com.demo.rediscache.serialization.CacheSerializer}).</li>
 *     <li>Los patrones usan el glob de Redis ({@code *}, {@code ?}, {@code [..]}) sobre la llave logica.</li>
 *     <li>Argumentos invalidos lanzan {@link IllegalArgumentException}; cualquier fallo de Redis lanza
 *     {@link com.demo.rediscache.exception.CacheException}, y el microservicio decide si ignorarlo
 *     (p. ej. ir a la base de datos) o propagarlo.</li>
 * </ul>
 */
public interface CacheService {

    /** Guarda un valor usando el TTL por defecto ({@code redis-cache.default-ttl}). */
    void set(String key, byte[] value);

    /** Guarda un valor con un TTL explicito. */
    void set(String key, byte[] value, Duration ttl);

    /** Guarda el valor solo si la llave no existe. Devuelve {@code true} si se guardo. */
    boolean setIfAbsent(String key, byte[] value, Duration ttl);

    /** Valor de la llave. Vacio si no existe. */
    Optional<byte[]> get(String key);

    /** Todas las llaves que coinciden con el patron junto con sus valores. */
    Map<String, byte[]> getAll(String pattern);

    /** Todas las llaves de este microservicio. */
    Set<String> keys();

    /** Llaves de este microservicio que coinciden con el patron. */
    Set<String> keys(String pattern);

    boolean exists(String key);

    boolean delete(String key);

    /** Elimina las llaves que coinciden con el patron. Devuelve cuantas elimino. */
    long deleteByPattern(String pattern);

    /** Redefine el TTL de una llave. Devuelve {@code false} si la llave no existe. */
    boolean expire(String key, Duration ttl);

    /** Tiempo de vida restante. Vacio si la llave no existe o no expira. */
    Optional<Duration> ttl(String key);
}
