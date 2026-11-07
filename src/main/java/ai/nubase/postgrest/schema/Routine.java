package ai.nubase.postgrest.schema;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Represents a database function/procedure
 * Equivalent to PostgREST's Routine type
 */
@Data
@Builder
public class Routine {
    private String schema;
    private String name;
    private String description;
    private List<RoutineParam> parameters;
    private String returnType;
    private boolean returnsSet;
    /** True when the return type is a composite (table or row) type. */
    private boolean returnsComposite;
    private String volatility; // VOLATILE, STABLE, IMMUTABLE
    private boolean hasVariadicParam;
}
