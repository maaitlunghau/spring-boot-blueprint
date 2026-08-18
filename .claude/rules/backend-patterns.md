# Code Templates — spring-boot-mini-project

Copy-paste starting points matching this project's actual style. See `architecture.md` for why each layer looks the way it does.

## Controller

```java
@RestController
@RequestMapping("/api/v1/<domain>")
public class <Domain>Controller {

    private final <Domain>Service <domain>Service;

    public <Domain>Controller(<Domain>Service <domain>Service) {
        this.<domain>Service = <domain>Service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<<Domain>Response>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(<domain>Service.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<<Domain>Response>> create(@Valid @RequestBody Create<Domain>Request request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.of(201, "Created", <domain>Service.create(request)));
    }
}
```

## Service (interface + impl only if you genuinely expect more than one implementation)

```java
public interface <Domain>Service {
    <Domain>Response getById(Long id);
    <Domain>Response create(Create<Domain>Request request);
}
```

```java
@Service
@Transactional(readOnly = true)
public class <Domain>ServiceImpl implements <Domain>Service {

    private final <Domain>Repository repository;

    public <Domain>ServiceImpl(<Domain>Repository repository) {
        this.repository = repository;
    }

    @Override
    public <Domain>Response getById(Long id) {
        return <Domain>Response.from(findOrThrow(id));
    }

    @Override
    @Transactional  // override: this one writes, so it can't stay read-only
    public <Domain>Response create(Create<Domain>Request request) {
        <Domain> entity = new <Domain>(request.name());
        return <Domain>Response.from(repository.save(entity));
    }

    private <Domain> findOrThrow(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("<Domain>", id));
    }
}
```

**Transactional gotcha, learned the hard way** (see the fixed-bugs section of `docs/known-issues.md`): a class-level `@Transactional(readOnly = true)` is inherited by every method that doesn't explicitly override it — including ones that write. With MySQL Connector/J's default `readOnlyPropagatesToServer`, that isn't just a lint smell: the database itself rejects the write. Every method that writes needs its own bare `@Transactional`. And if a method must commit *independently* of its caller's transaction (e.g. "revoke everything, then throw" — the revoke has to survive the throw), self-invocation bypasses the proxy: inject the service into itself via a `@Lazy @Autowired` field and call through that reference, with `@Transactional(propagation = Propagation.REQUIRES_NEW)` on the target method — see `RefreshTokenServiceImpl` for the real, working example.

## Repository

```java
public interface <Domain>Repository extends JpaRepository<<Domain>, Long> {
    Optional<<Domain>> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

## Entity

```java
@Entity
@Getter
@Table(name = "<domains>")
public class <Domain> extends BaseEntity {

    @Column(nullable = false)
    private String name;

    protected <Domain>() {}

    public <Domain>(String name) {
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
    }
}
```

## DTO (records)

```java
public record Create<Domain>Request(
    @NotBlank(message = "Name is required.") String name
) {}

public record <Domain>Response(Long id, String name) {
    public static <Domain>Response from(<Domain> entity) {
        return new <Domain>Response(entity.getId(), entity.getName());
    }
}
```

## Testing

Real `@SpringBootTest` against the actual docker-compose MySQL/Redis (`docker compose up -d` first) — this project doesn't use H2 or mocked repositories for anything that needs to prove real DB/transaction behavior (the transaction-propagation bugs in `docs/known-issues.md` would have slipped straight past a mock). Plain Mockito unit tests are for pure logic that doesn't depend on a real transaction or lock (e.g. branch-ordering checks) — see `RefreshTokenServiceImplTest` (unit) next to `RefreshTokenServiceImplIntegrationTest` (integration) for both styles side by side.
