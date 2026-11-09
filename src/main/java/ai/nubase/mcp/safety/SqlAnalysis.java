package ai.nubase.mcp.safety;

public record SqlAnalysis(SqlRisk risk, int statementCount, boolean hasUnknown) {
}
