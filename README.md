# redis-cache

Librería de cache con Redis (Redisson) para microservicios Quarkus (Java 21, Quarkus 3.39).

Da las operaciones sobre Redis y la serialización; **el flujo de cache lo decide cada microservicio**
en su capa de servicio (buscar en Redis → si no está, ir a la base de datos → guardar en Redis).

## Estructura
```
com.demo.rediscache
├── config
│   └── RedisCacheConfig              configuración redis-cache.*
├── domain
│   └── CacheOperation                operaciones (GET, SET, ...), se informan en las excepciones
├── exception
│   ├── CacheException                base abstracta: capturarla basta para degradar el servicio
│   ├── CacheConnectionException      Redis no disponible (conexión o timeout)
│   ├── CacheOperationException       Redis respondió con error
│   └── CacheSerializationException   el valor no se pudo convertir a byte[]
├── repository
│   ├── CacheRepository               acceso a Redis con llaves completas
│   └── impl/RedissonCacheRepository  única clase que conoce Redisson; traduce sus errores a CacheException
├── serialization
│   ├── CacheSerializer               objeto ↔ byte[]
│   └── impl/JacksonCacheSerializer   JSON UTF-8 con el ObjectMapper de la aplicación
└── service
    ├── CacheService                  API pública para los microservicios (llaves String, valores byte[])
    └── impl/CacheServiceImpl         valida argumentos, aplica prefijo y TTL por defecto, delega en el repositorio
```

Flujo de una llamada: `CacheService` → `CacheRepository` → Redisson → Redis.
Los microservicios solo usan `CacheService`, `CacheSerializer` y las excepciones.

Todas las implementaciones (`impl`) son `@DefaultBean`: si el microservicio declara su propio bean de
`CacheService`, `CacheRepository` o `CacheSerializer`, se usa ese.

## Instalación
```bash
mvn install        # la publica en el repositorio Maven local
```
Para que otros equipos la usen, publicarla en el repositorio corporativo (Nexus/Artifactory) con `mvn deploy`
agregando el `<distributionManagement>` correspondiente.

En el microservicio:
```xml
<dependency>
    <groupId>com.demo</groupId>
    <artifactId>redis-cache</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```
Trae Redisson (`redisson-quarkus-30`) y `quarkus-jackson` de forma transitiva: no hace falta declararlos.

## Configuración
```yaml
quarkus:
  redisson:                      # conexión (ref: https://redisson.pro/docs/configuration/)
    single-server-config:
      address: ${REDIS_URL:redis://localhost:6379}
      timeout: 2000
      retry-attempts: 1          # pocos reintentos: si Redis cae, el servicio se degrada rápido

redis-cache:
  key-prefix: "pedidos-ms:"      # OBLIGATORIO: aísla las llaves de cada microservicio
  default-ttl: 10m               # opcional, TTL de set(key, value) sin TTL explícito
```

## Uso
La librería no conoce el dominio de ningún microservicio: la llave es un `String` con el formato que cada uno
defina y el valor, cualquier objeto serializable. Ejemplo en un microservicio de pedidos:

```java
@ApplicationScoped
public class PedidoService {

    private final PedidoRepository repository;
    private final CacheService cache;
    private final CacheSerializer serializer;

    public PedidoDto obtener(Long id) {
        String key = "pedido:" + id;

        // 1. Redis
        boolean redisDisponible = true;
        try {
            Optional<PedidoDto> enCache = cache.get(key)
                    .flatMap(data -> serializer.deserialize(key, data, PedidoDto.class));
            if (enCache.isPresent()) {
                return enCache.get();
            }
        } catch (CacheException e) {
            redisDisponible = false;     // Redis caído: se sigue con la base de datos
        }

        // 2. Base de datos
        PedidoDto pedido = repository.findByIdOptional(id)
                .map(PedidoDto::from)
                .orElseThrow(() -> new NotFoundException("Pedido " + id));

        // 3. Guardar en Redis
        if (redisDisponible) {
            try {
                cache.set(key, serializer.serialize(key, pedido), Duration.ofMinutes(5));
            } catch (CacheException e) {
                // no se pudo cachear: igual se responde
            }
        }
        return pedido;
    }
}
```
Para tipos genéricos: `serializer.deserialize(key, data, new TypeReference<List<PedidoDto>>() {})`.

Si parte de la llave viene de una entrada del usuario, conviene validarla en el microservicio: un `*` o `?`
dentro de la llave la convertiría en un patrón al usarla en `keys` o `deleteByPattern`.

## Comportamiento
- **Redis no disponible:** la aplicación arranca igual; el cliente de Redisson se crea en la primera operación.
  Cada operación lanza `CacheConnectionException` y se reintenta la conexión en la siguiente.
- **Dato ilegible** (p. ej. cambió la estructura del DTO): `serializer.deserialize` devuelve vacío, así que se trata
  como si no estuviera en la cache y se sobrescribe al guardar.
- **Llaves:** son lógicas (`pedido:123`); en Redis quedan con el prefijo (`pedidos-ms:pedido:123`).
  `keys(...)` y `getAll(...)` devuelven las llaves sin prefijo y solo las de este microservicio.
- **Argumentos inválidos** (llave nula, valor nulo, TTL ≤ 0, patrón vacío): `IllegalArgumentException`,
  sin llegar a Redis.

## Tests
```bash
mvn test
```
| Test | Capa |
|---|---|
| `serialization/JacksonCacheSerializerTest` | Serialización, dato ilegible, error al serializar |
| `service/CacheServiceImplTest` | Prefijo, TTL por defecto, validaciones (repositorio mockeado) |
| `integration/RedisIntegrationTest` | Todas las capas contra un Redis real en `localhost:6379` (se omite si no hay uno) |
| `integration/RedisNoDisponibleTest` | Sin Redis la app arranca y cada operación lanza `CacheConnectionException` |
