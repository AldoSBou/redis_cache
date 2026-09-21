package com.demo.rediscache.serialization;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Optional;

/**
 * Convierte objetos a {@code byte[]} y viceversa para guardarlos con
 * {@link com.demo.rediscache.service.CacheService}. No accede a Redis: solo serializa.
 * <p>
 * La llave se recibe solo para informarla en logs y excepciones.
 */
public interface CacheSerializer {

    /**
     * Serializa el valor.
     *
     * @throws com.demo.rediscache.exception.CacheSerializationException si no se puede
     */
    byte[] serialize(String key, Object value);

    /**
     * Para tipos simples (p. ej. un DTO).
     * <p>
     * Devuelve vacio si el dato no se puede leer (p. ej. cambio la estructura del DTO): quien lo usa debe tratarlo
     * como si no estuviera en la cache, recargarlo y sobrescribirlo.
     */
    <T> Optional<T> deserialize(String key, byte[] data, Class<T> type);

    /** Para tipos genericos (p. ej. {@code List<MiDto>}). Mismo comportamiento que la version con clase. */
    <T> Optional<T> deserialize(String key, byte[] data, TypeReference<T> type);
}
