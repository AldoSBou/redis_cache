package com.demo.rediscache.exception;

import com.demo.rediscache.domain.CacheOperation;

/**
 * Base de todos los errores de la libreria. Capturar esta excepcion basta para degradar el servicio
 * (p. ej. ir a la base de datos); las subclases permiten distinguir la causa si hace falta.
 */
public abstract class CacheException extends RuntimeException {

    private final CacheOperation operation;
    private final String key;

    protected CacheException(CacheOperation operation, String key, String detail, Throwable cause) {
        super("Error en la operacion de cache %s sobre '%s': %s".formatted(operation, key, detail), cause);
        this.operation = operation;
        this.key = key;
    }

    public CacheOperation getOperation() {
        return operation;
    }

    /** Llave o patron sobre el que se hizo la operacion. */
    public String getKey() {
        return key;
    }
}
