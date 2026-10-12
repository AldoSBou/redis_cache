# redis-cache (rama `quarkus-redis-client`)

Librería de cache con Redis para microservicios Quarkus (Java 21, Quarkus 3.39), usando el cliente
oficial de Quarkus (`quarkus-redis-client`, basado en Vert.x) en lugar de Redisson.

Da las operaciones sobre Redis y la serialización; **el flujo de cache lo decide cada microservicio**
en su capa de servicio (buscar en Redis → si no está, ir a la base de datos → guardar en Redis).

## Diferencias con la rama Redisson
Solo cambian tres cosas; el resto de la librería (API pública, servicio, serializador, tests unitarios) es idéntico.

| | Rama Redisson | Rama quarkus-redis-client |
|---|---|---|
| Dependencia | `org.redisson:redisson-quarkus-30` | `io.quarkus:quarkus-redis-client` (viene en el BOM) |
| Implementación de `CacheRepository` | `RedissonCacheRepository` (`RedissonClient` + `ByteArrayCodec`) | `QuarkusRedisCacheRepository` (`RedisDataSource`, `ValueCommands<String, byte[]>`, `KeyCommands<String>`) |
| Configuración de conexión | `quarkus.redisson.*` | `quarkus.redis.*` |
| Imagen nativa | Requiere la config de reflexión que trae `redisson-quarkus-30` | Soportado de fábrica por la extensión |
| Dev Services | No | Sí: si no se configura `quarkus.redis.hosts`, Quarkus levanta un Redis en Docker en dev/test |

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
│   └── impl/QuarkusRedisCacheRepository  única clase que conoce el cliente de Quarkus; traduce sus errores a CacheException
├── serialization
│   ├── CacheSerializer               objeto ↔ byte[]
│   └── impl/JacksonCacheSerializer   JSON UTF-8 con el ObjectMapper de la aplicación
└── service
    ├── CacheService                  API pública para los microservicios (llaves String, valores byte[])
    └── impl/CacheServiceImpl         valida argumentos, aplica prefijo y TTL por defecto, delega en el repositorio
```

Flujo de una llamada: `CacheService` → `CacheRepository` → `RedisDataSource` (API bloqueante) → Redis.
Los microservicios solo usan `CacheService`, `CacheSerializer` y las excepciones.

Todas las implementaciones (`impl`) son `@DefaultBean`: si el microservicio declara su propio bean de
`CacheService`, `CacheRepository` o `CacheSerializer`, se usa ese.

## Instalación
```bash
mvn install        # la publica en el repositorio Maven local
```
El `pom.xml` genera el índice Jandex (`META-INF/jandex.idx`); sin él, Quarkus no descubre los beans de la
librería desde el microservicio.

En el microservicio:
```xml
<dependency>
    <groupId>com.demo</groupId>
    <artifactId>redis-cache</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```
Trae `quarkus-redis-client` y `quarkus-jackson` de forma transitiva: no hace falta declararlos.

## Configuración
```yaml
quarkus:
  redis:                         # conexión (ref: https://quarkus.io/guides/redis-reference)
    hosts: ${REDIS_URL:redis://localhost:6379}
    timeout: 2s                  # timeout de cada operación (API bloqueante)
    reconnect-attempts: 0        # sin reintentos: si Redis cae, el servicio se degrada rápido

redis-cache:
  key-prefix: "pedidos-ms:"      # OBLIGATORIO: aísla las llaves de cada microservicio
  default-ttl: 10m               # opcional, TTL de set(key, value) sin TTL explícito
```
Para TLS, password o cluster se usan las mismas propiedades `quarkus.redis.*` del cliente oficial
(`hosts: rediss://...`, `password`, `client-type: cluster`), sin tocar la librería.

## Uso
Idéntico a la rama Redisson: la llave es un `String` con el formato que cada microservicio defina y el valor,
cualquier objeto serializable.

```java
@ApplicationScoped
public class PedidoService {

    private final PedidoRepository repository;
    private final CacheService cache;
    private final CacheSerializer serializer;

    public PedidoDto obtener(Long id) {
        String key = "pedido:" + id;

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

        PedidoDto pedido = repository.findByIdOptional(id)
                .map(PedidoDto::from)
                .orElseThrow(() -> new NotFoundException("Pedido " + id));

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

## Comportamiento
- **Redis no disponible:** la aplicación arranca igual; el cliente se conecta en la primera operación.
  Cada operación lanza `CacheConnectionException` y se reintenta la conexión en la siguiente.
- **Dato ilegible:** `serializer.deserialize` devuelve vacío; se trata como si no estuviera en la cache.
- **Llaves:** son lógicas (`pedido:123`); en Redis quedan con el prefijo (`pedidos-ms:pedido:123`).
- **`setIfAbsent`:** usa `ValueCommands.setGet(key, value, nx().px(ttl))`, que manda `SET key value NX PX ttl GET` en un
  solo comando atómico y devuelve el valor anterior (`null` = se guardó). Requiere **Redis 7+** (combinar NX y GET).
- **Sin reactividad:** toda la librería usa la API bloqueante de `RedisDataSource` (Quarkus imperativo).
- **`ttl`:** el cliente de Quarkus lanza `RedisKeyNotFoundException` cuando la llave no existe; la librería lo
  traduce a `Optional.empty()`, igual que en la rama Redisson.
- **Argumentos inválidos:** `IllegalArgumentException`, sin llegar a Redis.

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

`src/test/resources/application.yml` desactiva Dev Services (`quarkus.redis.devservices.enabled: false`) para que
los tests no intenten levantar un Redis en Docker.
