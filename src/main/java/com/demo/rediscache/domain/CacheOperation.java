package com.demo.rediscache.domain;

/** Operaciones de cache; se informan en las excepciones para saber que fallo. */
public enum CacheOperation {
    SET,
    SET_IF_ABSENT,
    GET,
    GET_ALL,
    KEYS,
    EXISTS,
    DELETE,
    DELETE_BY_PATTERN,
    EXPIRE,
    TTL,
    SERIALIZE
}
