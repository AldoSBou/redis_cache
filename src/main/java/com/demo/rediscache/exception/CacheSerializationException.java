package com.demo.rediscache.exception;

import com.demo.rediscache.domain.CacheOperation;

/** El valor no se pudo convertir a {@code byte[]} para guardarlo. */
public class CacheSerializationException extends CacheException {

    public CacheSerializationException(String key, Throwable cause) {
        super(CacheOperation.SERIALIZE, key, cause.getMessage(), cause);
    }
}
