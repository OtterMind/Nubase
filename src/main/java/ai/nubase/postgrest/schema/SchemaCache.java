package ai.nubase.postgrest.schema;

import ai.nubase.postgrest.config.PostgRESTConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Schema Cache - Introspects PostgreSQL database schema
 * Equivalent to PostgREST's SchemaCache module
 *
 * NOTE: This class is NO LONGER a Spring singleton.
 * Each database has its own SchemaCache instance managed by SchemaCacheManager.
 *
 * Thread-Safety: Uses ReadWriteLock to ensure reload operations don't cause
 * empty cache reads. Queries will block during reload and get the new data.
 */
@Slf4j
public class SchemaCache {

    private final JdbcTemplate jdbcTemplate;
    private final PostgRESTConfig config;
    private final String databaseKey;  // NEW: Track which database this cache belongs to

    // ReadWriteLock to protect cache during reload
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();

    private volatile Map<String, Table> tables = new ConcurrentHashMap<>();
    private volatile Map<String, Routine> routines = new ConcurrentHashMap<>();
    private volatile Map<String, List<ForeignKey>> relationships = new ConcurrentHashMap<>();

    /**
     * Constructor - loads schema immediately
     *
     * @param jdbcTemplate JDBC template for the specific database
     * @param config PostgREST configuration for this database
     * @param databaseKey the database key (for logging)
     */
    public SchemaCache(JdbcTemplate jdbcTemplate, PostgRESTConfig config, String databaseKey) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
        this.databaseKey = databaseKey;

        // Load schema immediately upon construction
        loadSchema();
    }

    /**
     * Load or reload the schema cache
     * Can be called manually to refresh the cache
     */
    public void loadSchema() {
        log.info("[{}] Loading database schema cache...", databaseKey);
        loadTablesInto(this.tables);
        loadColumnsInto(this.tables);
        loadPrimaryKeysInto(this.tables);
        loadForeignKeysInto(this.tables,this.relationships);
        loadRoutinesInto(this.routines);
        logSchemaDetails(this.tables);
        log.info("[{}] Schema cache loaded: {} tables, {} routines",
            databaseKey, tables.size(), routines.size());
    }

    /**
     * Reload the schema cache using double-buffering to avoid empty cache during reload.
     * This method:
     * 1. Creates new temporary maps
     * 2. Loads schema into temporary maps
     * 3. Acquires write lock
     * 4. Atomically swaps references
     * 5. Releases write lock
     *
     * Queries that happen during reload will either see the old data or the new data,
     * but never empty data.
     */
    public void reload() {
        log.info("[{}] Reloading schema cache (using double-buffering)...", databaseKey);
        long startTime = System.currentTimeMillis();

        // Step 1: Create new temporary maps (double-buffering)
        Map<String, Table> newTables = new ConcurrentHashMap<>();
        Map<String, Routine> newRoutines = new ConcurrentHashMap<>();
        Map<String, List<ForeignKey>> newRelationships = new ConcurrentHashMap<>();

        // Step 2: Load schema into temporary maps (outside of lock)
        try {
            loadTablesInto(newTables);
            loadColumnsInto(newTables);
            loadPrimaryKeysInto(newTables);
            loadForeignKeysInto(newTables, newRelationships);
            loadRoutinesInto(newRoutines);

            // Step 3: Acquire write lock and swap references atomically
            cacheLock.writeLock().lock();
            try {
                this.tables = newTables;
                this.routines = newRoutines;
                this.relationships = newRelationships;
                logSchemaDetails(newTables);
                log.info("[{}] Schema cache reloaded: {} tables, {} routines (took {}ms)",
                    databaseKey, tables.size(), routines.size(),
                    System.currentTimeMillis() - startTime);
            } finally {
                cacheLock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("[{}] Failed to reload schema cache", databaseKey, e);
            // Keep old cache on error
        }
    }

    private void loadTablesInto(Map<String, Table> targetMap) {
        String sql = """
            SELECT
                schemaname as schema_name,
                tablename as table_name,
                obj_description((schemaname||'.'||tablename)::regclass) as description
            FROM pg_tables
            WHERE schemaname = ANY(?)
            UNION
            SELECT
                schemaname as schema_name,
                viewname as table_name,
                obj_description((schemaname||'.'||viewname)::regclass) as description
            FROM pg_views
            WHERE schemaname = ANY(?)
            ORDER BY schema_name, table_name
            """;

        String[] schemas = config.getDbSchemas().toArray(new String[0]);

        jdbcTemplate.query(sql, rs -> {
            String schema = rs.getString("schema_name");
            String tableName = rs.getString("table_name");
            String description = rs.getString("description");

            String key = schema + "." + tableName;
            targetMap.put(key, Table.builder()
                .schema(schema)
                .name(tableName)
                .description(description)
                .columns(new ArrayList<>())
                .primaryKey(new ArrayList<>())
                .foreignKeys(new HashMap<>())
                .build());
        }, (Object) schemas, (Object) schemas);
    }

    private void loadColumnsInto(Map<String, Table> targetMap) {
        String sql = """
            SELECT
                table_schema,
                table_name,
                column_name,
                data_type,
                udt_name,
                character_maximum_length,
                numeric_precision,
                numeric_scale,
                is_nullable,
                column_default,
                ordinal_position,
                col_description((table_schema||'.'||table_name)::regclass, ordinal_position) as description
            FROM information_schema.columns
            WHERE table_schema = ANY(?)
            ORDER BY table_schema, table_name, ordinal_position
            """;

        String[] schemas = config.getDbSchemas().toArray(new String[0]);

        jdbcTemplate.query(sql, rs -> {
            String schema = rs.getString("table_schema");
            String tableName = rs.getString("table_name");
            String key = schema + "." + tableName;

            Table table = targetMap.get(key);
            if (table != null) {
                Column column = Column.builder()
                    .name(rs.getString("column_name"))
                    .dataType(rs.getString("data_type"))
                    .udtName(rs.getString("udt_name"))
                    .maxLength(rs.getObject("character_maximum_length", Integer.class))
                    .numericPrecision(rs.getObject("numeric_precision", Integer.class))
                    .numericScale(rs.getObject("numeric_scale", Integer.class))
                    .nullable("YES".equals(rs.getString("is_nullable")))
                    .hasDefault(rs.getString("column_default") != null)
                    .defaultValue(rs.getString("column_default"))
                    .position(rs.getInt("ordinal_position"))
                    .description(rs.getString("description"))
                    .build();

                table.getColumns().add(column);
            }
        }, (Object) schemas);
    }

    private void loadPrimaryKeysInto(Map<String, Table> targetMap) {
        String sql = """
            SELECT
                tc.table_schema,
                tc.table_name,
                kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
            WHERE tc.constraint_type = 'PRIMARY KEY'
                AND tc.table_schema = ANY(?)
            ORDER BY tc.table_schema, tc.table_name, kcu.ordinal_position
            """;

        String[] schemas = config.getDbSchemas().toArray(new String[0]);

        jdbcTemplate.query(sql, rs -> {
            String schema = rs.getString("table_schema");
            String tableName = rs.getString("table_name");
            String columnName = rs.getString("column_name");
            String key = schema + "." + tableName;

            Table table = targetMap.get(key);
            if (table != null) {
                table.getPrimaryKey().add(columnName);
            }
        }, (Object) schemas);
    }

    private void loadForeignKeysInto(Map<String, Table> targetTablesMap,
                                      Map<String, List<ForeignKey>> targetRelationshipsMap) {
        String sql = """
            SELECT
                tc.constraint_name,
                tc.table_schema AS source_schema,
                tc.table_name AS source_table,
                kcu.column_name AS source_column,
                ccu.table_schema AS target_schema,
                ccu.table_name AS target_table,
                ccu.column_name AS target_column
            FROM information_schema.table_constraints AS tc
            JOIN information_schema.key_column_usage AS kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage AS ccu
                ON ccu.constraint_name = tc.constraint_name
                AND ccu.table_schema = tc.table_schema
            WHERE tc.constraint_type = 'FOREIGN KEY'
                AND tc.table_schema = ANY(?)
            ORDER BY tc.constraint_name, kcu.ordinal_position
            """;

        String[] schemas = config.getDbSchemas().toArray(new String[0]);

        Map<String, ForeignKey.ForeignKeyBuilder> fkBuilders = new HashMap<>();

        jdbcTemplate.query(sql, rs -> {
            String constraintName = rs.getString("constraint_name");

            ForeignKey.ForeignKeyBuilder builder = fkBuilders.computeIfAbsent(
                constraintName,
                k -> {
                  try {
                    return ForeignKey.builder()
                        .constraintName(constraintName)
                        .sourceSchema(rs.getString("source_schema"))
                        .sourceTable(rs.getString("source_table"))
                        .targetSchema(rs.getString("target_schema"))
                        .targetTable(rs.getString("target_table"))
                        .sourceColumns(new ArrayList<>())
                        .targetColumns(new ArrayList<>());
                  } catch (SQLException e) {
                    throw new RuntimeException(e);
                  }
                }
            );
            builder.sourceColumn(rs.getString("source_column"));
            builder.targetColumn(rs.getString("target_column"));
        }, (Object) schemas);

        // Add foreign keys to tables
        fkBuilders.forEach((name, builder) -> {
            ForeignKey fk = builder.build();
            String tableKey = fk.getSourceSchema() + "." + fk.getSourceTable();
            Table table = targetTablesMap.get(tableKey);
            if (table != null) {
                table.getForeignKeys().put(name, fk);
            }

            // Also maintain relationships map for querying
            targetRelationshipsMap.computeIfAbsent(tableKey, k -> new ArrayList<>()).add(fk);
        });
    }

    private void loadRoutinesInto(Map<String, Routine> targetMap) {
        String sql = """
            SELECT
                n.nspname as schema_name,
                p.proname as routine_name,
                pg_get_function_result(p.oid) as return_type,
                p.proretset as returns_set,
                COALESCE(rt.typtype = 'c', false) as returns_composite,
                p.provolatile as volatility,
                p.provariadic > 0 as has_variadic,
                obj_description(p.oid) as description
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            LEFT JOIN pg_type rt ON rt.oid = p.prorettype
            WHERE n.nspname = ANY(?)
                AND p.prokind = 'f'
            ORDER BY schema_name, routine_name
            """;

        String[] schemas = config.getDbSchemas().toArray(new String[0]);

        jdbcTemplate.query(sql, rs -> {
            String schema = rs.getString("schema_name");
            String name = rs.getString("routine_name");
            String key = schema + "." + name;

            String volatilityCode = rs.getString("volatility");
            String volatility = switch (volatilityCode) {
                case "i" -> "IMMUTABLE";
                case "s" -> "STABLE";
                default -> "VOLATILE";
            };

            targetMap.put(key, Routine.builder()
                .schema(schema)
                .name(name)
                .description(rs.getString("description"))
                .returnType(rs.getString("return_type"))
                .returnsSet(rs.getBoolean("returns_set"))
                .returnsComposite(rs.getBoolean("returns_composite"))
                .volatility(volatility)
                .hasVariadicParam(rs.getBoolean("has_variadic"))
                .parameters(new ArrayList<>())
                .build());
        }, (Object) schemas);

        // Load routine parameters
        loadRoutineParametersInto(targetMap);
    }

    private void loadRoutineParametersInto(Map<String, Routine> targetMap) {
        String sql = """
            SELECT
                n.nspname as schema_name,
                p.proname as routine_name,
                unnest(COALESCE(p.proargnames, ARRAY[]::text[])) as param_name,
                unnest(string_to_array(pg_get_function_arguments(p.oid), ', ')) as param_def
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname = ANY(?)
                AND p.prokind = 'f'
                AND p.pronargs > 0
            ORDER BY schema_name, routine_name
            """;

        String[] schemas = config.getDbSchemas().toArray(new String[0]);

        // Track parameters by routine key
        Map<String, List<RoutineParam>> paramsByRoutine = new HashMap<>();

        jdbcTemplate.query(sql, rs -> {
            String schema = rs.getString("schema_name");
            String name = rs.getString("routine_name");
            String key = schema + "." + name;

            String paramName = rs.getString("param_name");
            String paramDef = rs.getString("param_def");

            // Parse parameter definition (e.g., "p_id integer", "p_name text DEFAULT 'test'")
            RoutineParam param = parseRoutineParam(paramName, paramDef);
            if (param != null) {
                paramsByRoutine.computeIfAbsent(key, k -> new ArrayList<>()).add(param);
            }
        }, (Object) schemas);

        // Attach parameters to routines
        for (Map.Entry<String, List<RoutineParam>> entry : paramsByRoutine.entrySet()) {
            Routine routine = targetMap.get(entry.getKey());
            if (routine != null) {
                routine.setParameters(entry.getValue());
            }
        }
    }

    /**
     * Parse a routine parameter definition.
     * Examples:
     *   "p_id integer" -> name=p_id, type=integer, required=true
     *   "p_name text DEFAULT 'test'" -> name=p_name, type=text, required=false, default='test'
     *   "VARIADIC p_items text[]" -> name=p_items, type=text[], variadic=true
     */
    private RoutineParam parseRoutineParam(String paramName, String paramDef) {
        if (paramDef == null || paramDef.strip().isEmpty()) {
            return null;
        }

        String def = paramDef.strip();
        boolean variadic = false;
        boolean required = true;
        String defaultValue = null;
        String dataType = null;

        // Check for VARIADIC
        if (def.toUpperCase().startsWith("VARIADIC ")) {
            variadic = true;
            def = def.substring(9).strip();
        }

        // Check for DEFAULT
        int defaultIndex = def.toUpperCase().indexOf(" DEFAULT ");
        if (defaultIndex > 0) {
            required = false;
            defaultValue = def.substring(defaultIndex + 9).strip();
            def = def.substring(0, defaultIndex).strip();
        }

        // Extract data type - the remaining part after removing param name
        // Parameter definition format: "param_name type" or just "type" if name is null
        if (paramName != null && !paramName.isEmpty() && def.startsWith(paramName)) {
            dataType = def.substring(paramName.length()).strip();
        } else {
            // Try to extract type from "name type" format
            String[] parts = def.split("\\s+", 2);
            if (parts.length == 2) {
                dataType = parts[1];
            } else {
                dataType = def;
            }
        }

        // Clean up parameter name (might be null or empty for unnamed params)
        String finalName = (paramName != null && !paramName.isEmpty()) ? paramName : null;

        return RoutineParam.builder()
            .name(finalName)
            .dataType(dataType)
            .required(required)
            .variadic(variadic)
            .defaultValue(defaultValue)
            .build();
    }

    /**
     * Get table metadata with read lock protection.
     * This ensures the cache is never empty during reload operations.
     */
    public Table getTable(String schema, String name) {
        cacheLock.readLock().lock();
        try {
            return tables.get(schema + "." + name);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Get routine metadata with read lock protection.
     * This ensures the cache is never empty during reload operations.
     */
    public Routine getRoutine(String schema, String name) {
        cacheLock.readLock().lock();
        try {
            return routines.get(schema + "." + name);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Get relationships metadata with read lock protection.
     * This ensures the cache is never empty during reload operations.
     */
    public List<ForeignKey> getRelationships(String schema, String table) {
        cacheLock.readLock().lock();
        try {
            return relationships.getOrDefault(schema + "." + table, Collections.emptyList());
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Get all tables with read lock protection.
     * Returns an unmodifiable view to prevent external modification.
     */
    public Map<String, Table> getTables() {
        cacheLock.readLock().lock();
        try {
            return Collections.unmodifiableMap(tables);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Get all routines with read lock protection.
     * Returns an unmodifiable view to prevent external modification.
     */
    public Map<String, Routine> getRoutines() {
        cacheLock.readLock().lock();
        try {
            return Collections.unmodifiableMap(routines);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Get all relationships with read lock protection.
     * Returns an unmodifiable view to prevent external modification.
     */
    public Map<String, List<ForeignKey>> getRelationships() {
        cacheLock.readLock().lock();
        try {
            return Collections.unmodifiableMap(relationships);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Log detailed schema information for debugging.
     * Prints each table with all column names and their udt types.
     */
    private void logSchemaDetails(Map<String, Table> targetMap) {
        for (Map.Entry<String, Table> entry : targetMap.entrySet()) {
            Table table = entry.getValue();
            if (table.getColumns() == null || table.getColumns().isEmpty()) {
                log.info("{}_Schema_detail - table: {} (no columns)", databaseKey, entry.getKey());
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (Column col : table.getColumns()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(col.getName()).append(":").append(col.getUdtName() != null ? col.getUdtName() : col.getDataType());
            }
            log.info("{}_Schema_detail - table: {} columns: [{}]", databaseKey, entry.getKey(), sb);
        }
    }
}
