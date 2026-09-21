package com.demo.rediscache.exception;

import com.demo.rediscache.domain.CacheOperation;

/** Redis respondio con un error (p. ej. la llave guarda un tipo de dato distinto al esperado). */
public class CacheOperationException extends CacheException {

    public CacheOperationException(CacheOperation operation, String key, Throwable cause) {
        super(operation, key, cause.getMessage(), cause);
    }
}
