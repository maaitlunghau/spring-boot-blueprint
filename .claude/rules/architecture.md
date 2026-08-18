# Architecture — spring-boot-blueprint

No domain/business code exists yet. The only Java source is:

```
src/main/java/com/maaitlunghau/spring_boot_blueprint/SpringBootBlueprintApplication.java
src/test/java/com/maaitlunghau/spring_boot_blueprint/SpringBootBlueprintApplicationTests.java
```

There is no established module/layer convention to follow yet — no `controller/`, `service/`, `repository/`, `entity/`, or `dto/` packages exist. When the first real feature is added, decide the package layout then and update this file (and `coding-standards.md`/`backend-patterns.md`) to describe what was actually built — don't invent a convention here ahead of real code.

## Config layout that does exist

```
src/main/resources/
├── application.yml        ← shared config: datasource creds, redis, server port, app.jwt.* (unused by any code yet)
├── application-dev.yml    ← profile: ddl-auto=update, show-sql=true, flyway disabled
├── application-prod.yml   ← profile: ddl-auto=validate, show-sql=false, flyway enabled
└── db/migration/
    └── V1__init.sql       ← empty baseline, no entities to migrate yet
```

`spring.profiles.default: dev` in `application.yml` — set `SPRING_PROFILES_ACTIVE=prod` explicitly for a production run.
