package com.demo.rediscache.exception;

import com.demo.rediscache.domain.CacheOperation;

/** Redis no esta disponible: no se pudo conectar o la operacion supero el timeout. */
public class CacheConnectionException extends CacheException {

    public CacheConnectionException(CacheOperation operation, String key, Throwable cause) {
        super(operation, key, cause.getMessage(), cause);
    }
}
