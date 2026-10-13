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

> Toda la configuración va bajo un **único** bloque `quarkus:`. Si el `application.yml` ya tiene uno (datasource,
> http, ...), `redis:` se agrega dentro de él; dos bloques `quarkus:` hacen que uno pise al otro.

### Pool de conexiones
El cliente de Quarkus (Vert.x) mantiene su propio pool; no hay que crear uno aparte. Las conexiones se abren bajo
demanda hasta `max-pool-size` (no hay un mínimo de conexiones ociosas) y el *cleaner* cierra las que llevan más de
`pool-recycle-timeout` sin usarse. Como la librería usa la API bloqueante, **cada operación ocupa una conexión mientras
dura**, así que `max-pool-size` debe cubrir las operaciones de cache simultáneas del servicio.

```yaml
quarkus:
  redis:
    hosts: ${REDIS_URL:redis://localhost:6379}
    password: ${REDIS_PASSWORD:}         # omitir si Redis no tiene auth
    timeout: 2s
    reconnect-attempts: 0
    # --- pool ---
    max-pool-size: 16                    # conexiones máximas (defecto 6)
    max-pool-waiting: 32                 # operaciones en cola cuando el pool está lleno (defecto 24)
    pool-cleaner-interval: 30s           # cada cuánto se revisan las conexiones ociosas (defecto 30s)
    pool-recycle-timeout: 3m             # ociosa más de esto → se cierra (defecto 3m)
    # --- TCP ---
    tcp:
      connection-timeout: 2s             # timeout para abrir cada conexión nueva
      keep-alive: true                   # detecta conexiones rotas por firewall/NAT
      no-delay: true
```

Cómo dimensionarlo:

| Parámetro | Qué controla | Guía |
|---|---|---|
| `max-pool-size` | Tope de conexiones abiertas | Operaciones de cache concurrentes esperadas. 6 (defecto) alcanza para una API chica; 16–32 para servicios con tráfico. |
| `max-pool-waiting` | Cuántas operaciones esperan en cola cuando no hay conexión libre | ≈ 2× `max-pool-size`. Si se supera, la operación falla al instante con `ConnectionPoolTooBusyException`, que la librería traduce a `CacheConnectionException`: el servicio sigue con la base de datos. |
| `timeout` | Tiempo máximo de cada operación bloqueante, incluida la espera por una conexión del pool | 1–2 s. Si aparece con Redis sano, el pool está corto: subir `max-pool-size`. |
| `pool-recycle-timeout` | Cuánto vive una conexión ociosa | 3 m por defecto está bien; bajar si Redis cierra conexiones inactivas antes (servicios administrados). |
| `tcp.connection-timeout` | Timeout al abrir una conexión nueva | Igual o menor que `timeout`, para que la operación falle por conexión y no por timeout genérico. |

El total de conexiones que recibe Redis es `réplicas × max-pool-size` y debe quedar por debajo del `maxclients` del
servidor (10 000 por defecto en Redis; menor en los planes básicos de servicios administrados como Azure Cache for
Redis). Ejemplo: 6 réplicas × 16 = 96 conexiones como máximo.

Referencia completa: https://quarkus.io/guides/redis-reference#redis-configuration-reference

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
