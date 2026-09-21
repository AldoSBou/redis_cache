package com.demo.rediscache.serialization;

import com.demo.rediscache.exception.CacheSerializationException;
import com.demo.rediscache.serialization.impl.JacksonCacheSerializer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonCacheSerializerTest {

    private static final String KEY = "pedido:1";

    private final CacheSerializer serializer =
            new JacksonCacheSerializer(new ObjectMapper().registerModule(new JavaTimeModule()));

    /** Tipo cualquiera: el serializador no conoce el dominio de ningun microservicio. */
    record Dato(String nombre, LocalDate fecha) {
    }

    /** Jackson no puede serializar un objeto sin propiedades. */
    static class SinPropiedades {
    }

    @Test
    void idaYVueltaConClase() {
        Dato dato = new Dato("a", LocalDate.of(2025, 1, 1));

        byte[] bytes = serializer.serialize(KEY, dato);

        assertEquals(dato, serializer.deserialize(KEY, bytes, Dato.class).orElseThrow());
    }

    @Test
    void idaYVueltaConTipoGenerico() {
        List<Dato> lista = List.of(new Dato("a", null), new Dato("b", LocalDate.of(2025, 2, 1)));

        byte[] bytes = serializer.serialize(KEY, lista);

        assertEquals(lista, serializer.deserialize(KEY, bytes, new TypeReference<List<Dato>>() {
        }).orElseThrow());
    }

    @Test
    void datoIlegible_devuelveVacio() {
        byte[] basura = "no es json".getBytes(StandardCharsets.UTF_8);

        assertTrue(serializer.deserialize(KEY, basura, Dato.class).isEmpty());
    }

    @Test
    void valorNoSerializable_lanzaCacheSerializationException() {
        CacheSerializationException e = assertThrows(CacheSerializationException.class,
                () -> serializer.serialize(KEY, new SinPropiedades()));
        assertEquals("pedido:1", e.getKey());
    }
}
