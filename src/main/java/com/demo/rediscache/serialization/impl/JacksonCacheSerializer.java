package com.demo.rediscache.serialization.impl;

import com.demo.rediscache.exception.CacheSerializationException;
import com.demo.rediscache.serialization.CacheSerializer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * {@link CacheSerializer} con JSON en UTF-8. Usa el {@link ObjectMapper} de la aplicacion, por lo que respeta
 * sus personalizaciones (modulos, formato de fechas, etc.) y el dato se puede leer con {@code redis-cli GET}.
 */
@ApplicationScoped
@DefaultBean
public class JacksonCacheSerializer implements CacheSerializer {

    private static final Logger LOG = Logger.getLogger(JacksonCacheSerializer.class);

    private final ObjectMapper mapper;

    public JacksonCacheSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public byte[] serialize(String key, Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new CacheSerializationException(key, e);
        }
    }

    @Override
    public <T> Optional<T> deserialize(String key, byte[] data, Class<T> type) {
        return deserialize(key, data, mapper.constructType(type));
    }

    @Override
    public <T> Optional<T> deserialize(String key, byte[] data, TypeReference<T> type) {
        return deserialize(key, data, mapper.getTypeFactory().constructType(type));
    }

    private <T> Optional<T> deserialize(String key, byte[] data, JavaType type) {
        try {
            return Optional.ofNullable(mapper.readValue(data, type));
        } catch (IOException e) {
            LOG.warnf("Dato invalido en la cache '%s', se ignora: %s", key, e.getMessage());
            return Optional.empty();
        }
    }
}
