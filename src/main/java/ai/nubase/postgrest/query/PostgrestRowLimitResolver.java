package ai.nubase.postgrest.query;

import ai.nubase.postgrest.api.RangeHeader;

/**
 * 将 PostgREST 的 {@code db-max-rows} 与客户端 Range/limit 合并为 SQL 分页参数。
 *
 * <p>规则（对齐 PostgREST GET 读路径）：
 * <ul>
 *   <li>无 Range 且配置了 {@code db_max_rows} → 默认 {@code LIMIT db_max_rows}</li>
 *   <li>Range 请求行数超过上限 → 截断到 {@code db_max_rows}</li>
 *   <li>开区间 Range（如 {@code items=10-}）→ 从 offset 起最多取 {@code db_max_rows} 行</li>
 *   <li>{@code db_max_rows} 为 null 或 ≤0 → 不施加服务端默认上限（无 Range 时不 LIMIT）</li>
 * </ul>
 */
public final class PostgrestRowLimitResolver {

    public record ResolvedPagination(Long offset, Long limit) {}

    private PostgrestRowLimitResolver() {}

    public static ResolvedPagination resolve(RangeHeader range, Integer dbMaxRows) {
        Integer cap = effectiveCap(dbMaxRows);
        if (range == null) {
            return cap == null
                    ? new ResolvedPagination(null, null)
                    : new ResolvedPagination(0L, cap.longValue());
        }
        if (cap == null) {
            return legacyClientRange(range);
        }
        return cappedRange(range, cap.longValue());
    }

    /** 无服务端 cap 时保持 QueryPlanner 原有 Range 语义。 */
    private static ResolvedPagination legacyClientRange(RangeHeader range) {
        Long offset = range.getStart();
        Long limit = null;
        if (range.getEnd() != null && range.getStart() != null) {
            limit = range.getEnd() - range.getStart() + 1;
        }
        return new ResolvedPagination(offset, limit);
    }

    private static ResolvedPagination cappedRange(RangeHeader range, long cap) {
        Long offset = range.getStart();
        long resolvedOffset = offset != null ? offset : 0L;

        if (range.getEnd() == null) {
            return new ResolvedPagination(resolvedOffset, cap);
        }
        if (range.getStart() == null) {
            long requested = range.getEnd() + 1;
            return new ResolvedPagination(null, Math.min(requested, cap));
        }
        long requested = range.getEnd() - range.getStart() + 1;
        return new ResolvedPagination(resolvedOffset, Math.min(requested, cap));
    }

    private static Integer effectiveCap(Integer dbMaxRows) {
        if (dbMaxRows == null || dbMaxRows <= 0) {
            return null;
        }
        return dbMaxRows;
    }
}
