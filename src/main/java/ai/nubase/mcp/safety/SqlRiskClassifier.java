package ai.nubase.mcp.safety;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SqlRiskClassifier {

    private static final Pattern COPY_PROGRAM = Pattern.compile("\\bcopy\\b.*\\bprogram\\b");
    private static final Pattern DOLLAR_QUOTE_TAG = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern PRIVILEGED_IDENTITY_OR_DATABASE_OPERATION = Pattern.compile(
            "^(?:alter\\s+(?:role|user|group|database|tablespace|default\\s+privileges)\\b"
                    + "|create\\s+(?:role|user|group|database|tablespace)\\b"
                    + "|reassign\\s+owned\\b"
                    + "|alter\\b.*\\bowner\\s+to\\b)"
    );
    private static final Pattern NESTED_DATA_WRITE = Pattern.compile(
            "\\b(?:insert\\s+into|update\\s+|delete\\s+from|merge\\s+into|copy\\s+)"
    );
    private static final Set<String> UNSAFE_CREATE_TABLE_CLAUSES = Set.of(
            "as", "inherits", "of", "on", "partition", "tablespace", "using", "with", "without"
    );
    private static final Set<String> UNSAFE_ADD_COLUMN_CLAUSES = Set.of(
            "add", "alter", "attach", "check", "collate", "compression", "constraint",
            "default", "detach", "disable", "drop", "enable", "exclude", "force", "foreign",
            "generated", "identity", "inherit", "no", "of", "owner", "period", "primary",
            "references", "rename", "replica", "reset", "serial", "set", "smallserial",
            "bigserial", "statistics", "storage", "unique", "using", "validate"
    );

    public SqlRisk classify(String sql) {
        if (containsAmbiguousPlainString(sql)) {
            return SqlRisk.UNKNOWN;
        }
        String[] statements = splitStatements(sql);
        if (statements.length == 0) {
            return SqlRisk.UNKNOWN;
        }
        SqlRisk highest = SqlRisk.UNKNOWN;
        for (String statement : statements) {
            SqlRisk current = classifyStatement(statement);
            if (current == SqlRisk.UNKNOWN) {
                return SqlRisk.UNKNOWN;
            }
            highest = max(highest, current);
        }
        return highest;
    }

    public int countStatements(String sql) {
        return splitStatements(sql).length;
    }

    private SqlRisk classifyStatement(String statement) {
        String normalized = normalize(statement);
        if (normalized.isBlank()) {
            return SqlRisk.UNKNOWN;
        }
        if (COPY_PROGRAM.matcher(normalized).find()) {
            return SqlRisk.DANGEROUS;
        }
        if (PRIVILEGED_IDENTITY_OR_DATABASE_OPERATION.matcher(normalized).find()) {
            return SqlRisk.DANGEROUS;
        }
        if (startsWithAny(normalized,
                "drop ", "truncate ", "reindex ", "vacuum ", "cluster ",
                "alter system", "create role", "create user", "grant ", "revoke ",
                "set role", "set session authorization", "do ", "call ")) {
            return SqlRisk.DANGEROUS;
        }
        if (containsUnboundedDelete(normalized)) {
            return SqlRisk.DANGEROUS;
        }
        List<String> topLevelTokens = topLevelTokenValues(normalized);
        if (!topLevelTokens.isEmpty() && topLevelTokens.get(0).equals("create")) {
            return classifyCreate(topLevelTokens);
        }
        if (!topLevelTokens.isEmpty() && topLevelTokens.get(0).equals("alter")) {
            return classifyAlter(topLevelTokens);
        }
        if (startsWithAny(normalized, "comment ", "security label ")) {
            return SqlRisk.SCHEMA_WRITE;
        }
        if (startsWithAny(normalized,
                "insert ", "update ", "delete ", "merge ", "copy ")) {
            return SqlRisk.DATA_WRITE;
        }
        if (startsWithAny(normalized, "with ", "with recursive ", "explain ")
                && NESTED_DATA_WRITE.matcher(normalized).find()) {
            return SqlRisk.DATA_WRITE;
        }
        if (startsWithAny(normalized,
                "select ", "with ", "show ", "explain ", "describe ")) {
            return SqlRisk.READ;
        }
        return SqlRisk.UNKNOWN;
    }

    private SqlRisk classifyCreate(List<String> tokens) {
        int index = 1;
        if (index >= tokens.size()) {
            return SqlRisk.UNKNOWN;
        }
        if (tokens.get(index).equals("or")) {
            return SqlRisk.DANGEROUS;
        }
        if (Set.of("temp", "temporary", "unlogged").contains(tokens.get(index))) {
            index++;
        }
        if (index >= tokens.size() || !tokens.get(index).equals("table")) {
            return SqlRisk.DANGEROUS;
        }
        index++;
        if (hasTokenSequence(tokens, index, "if", "not", "exists")) {
            index += 3;
        }
        List<String> trailingTokens = tokens.subList(index, tokens.size());
        if (trailingTokens.stream().anyMatch(UNSAFE_CREATE_TABLE_CLAUSES::contains)) {
            return SqlRisk.DANGEROUS;
        }
        return trailingTokens.size() <= 2 ? SqlRisk.SCHEMA_WRITE : SqlRisk.DANGEROUS;
    }

    private SqlRisk classifyAlter(List<String> tokens) {
        if (tokens.size() < 3 || !tokens.get(1).equals("table")) {
            return SqlRisk.DANGEROUS;
        }
        int addIndex = tokens.indexOf("add");
        if (addIndex < 0 || tokens.lastIndexOf("add") != addIndex) {
            return SqlRisk.DANGEROUS;
        }
        List<String> targetTokens = tokens.subList(2, addIndex).stream()
                .filter(token -> !Set.of("exists", "if", "only").contains(token))
                .toList();
        if (targetTokens.size() > 2) {
            return SqlRisk.DANGEROUS;
        }
        int actionIndex = addIndex + 1;
        if (actionIndex >= tokens.size() || !tokens.get(actionIndex).equals("column")) {
            return SqlRisk.DANGEROUS;
        }
        actionIndex++;
        if (hasTokenSequence(tokens, actionIndex, "if", "not", "exists")) {
            actionIndex += 3;
        }
        List<String> columnTokens = tokens.subList(actionIndex, tokens.size());
        if (columnTokens.stream().anyMatch(UNSAFE_ADD_COLUMN_CLAUSES::contains)) {
            return SqlRisk.DANGEROUS;
        }
        return SqlRisk.SCHEMA_WRITE;
    }

    private boolean hasTokenSequence(List<String> tokens, int start, String... expected) {
        if (start + expected.length > tokens.size()) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (!tokens.get(start + index).equals(expected[index])) {
                return false;
            }
        }
        return true;
    }

    private List<String> topLevelTokenValues(String statement) {
        return scanTokens(statement).stream()
                .filter(token -> token.depth() == 0)
                .map(SqlToken::value)
                .toList();
    }

    private boolean containsUnboundedDelete(String statement) {
        List<SqlToken> tokens = scanTokens(statement);
        for (int index = 0; index < tokens.size(); index++) {
            SqlToken token = tokens.get(index);
            if (!token.value().equals("delete")) {
                continue;
            }
            boolean hasFrom = false;
            boolean hasWhere = false;
            for (int following = index + 1; following < tokens.size(); following++) {
                SqlToken candidate = tokens.get(following);
                if (candidate.depth() < token.depth()) {
                    break;
                }
                if (candidate.depth() != token.depth()) {
                    continue;
                }
                if (candidate.value().equals("from")) {
                    hasFrom = true;
                } else if (candidate.value().equals("where")) {
                    hasWhere = true;
                }
            }
            if (hasFrom && !hasWhere) {
                return true;
            }
        }
        return false;
    }

    private List<SqlToken> scanTokens(String statement) {
        List<SqlToken> tokens = new ArrayList<>();
        int depth = 0;
        int index = 0;
        while (index < statement.length()) {
            char current = statement.charAt(index);
            if (current == '\'' || current == '"') {
                index = skipQuoted(statement, index, current);
                continue;
            }
            if (current == '$') {
                int nextIndex = skipDollarQuoted(statement, index);
                if (nextIndex > index + 1) {
                    index = nextIndex;
                    continue;
                }
            }
            if (current == '(') {
                depth++;
                index++;
                continue;
            }
            if (current == ')') {
                depth = Math.max(0, depth - 1);
                index++;
                continue;
            }
            if (Character.isJavaIdentifierStart(current)) {
                int tokenEnd = index + 1;
                while (tokenEnd < statement.length()
                        && Character.isJavaIdentifierPart(statement.charAt(tokenEnd))) {
                    tokenEnd++;
                }
                tokens.add(new SqlToken(statement.substring(index, tokenEnd), depth));
                index = tokenEnd;
                continue;
            }
            index++;
        }
        return tokens;
    }

    private boolean containsAmbiguousPlainString(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '"') {
                index = skipQuoted(sql, index, current);
                continue;
            }
            if (current == '\'') {
                boolean escapeString = isEscapeStringLiteral(sql, index);
                int quotedEnd = skipQuoted(sql, index, current);
                if (!escapeString && containsBackslash(sql, index + 1, quotedEnd)) {
                    return true;
                }
                index = quotedEnd;
                continue;
            }
            if (current == '$') {
                int quotedEnd = skipDollarQuoted(sql, index);
                if (quotedEnd > index + 1) {
                    index = quotedEnd;
                    continue;
                }
            }
            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                index = skipLineComment(sql, index);
                continue;
            }
            if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                index = skipBlockComment(sql, index);
                continue;
            }
            index++;
        }
        return false;
    }

    private boolean containsBackslash(String value, int start, int end) {
        for (int index = start; index < end; index++) {
            if (value.charAt(index) == '\\') {
                return true;
            }
        }
        return false;
    }

    private int skipQuoted(String statement, int quoteStart, char quote) {
        boolean escapeBackslashes = quote == '\'' && isEscapeStringLiteral(statement, quoteStart);
        int index = quoteStart + 1;
        while (index < statement.length()) {
            if (escapeBackslashes && statement.charAt(index) == '\\' && index + 1 < statement.length()) {
                index += 2;
                continue;
            }
            if (statement.charAt(index) != quote) {
                index++;
                continue;
            }
            if (index + 1 < statement.length() && statement.charAt(index + 1) == quote) {
                index += 2;
                continue;
            }
            return index + 1;
        }
        return statement.length();
    }

    private boolean isEscapeStringLiteral(String statement, int quoteStart) {
        if (quoteStart == 0 || Character.toLowerCase(statement.charAt(quoteStart - 1)) != 'e') {
            return false;
        }
        return quoteStart == 1 || !isSqlIdentifierPart(statement.charAt(quoteStart - 2));
    }

    private int skipDollarQuoted(String statement, int delimiterStart) {
        if (delimiterStart > 0 && isSqlIdentifierPart(statement.charAt(delimiterStart - 1))) {
            return delimiterStart + 1;
        }
        int delimiterEnd = statement.indexOf('$', delimiterStart + 1);
        if (delimiterEnd < 0) {
            return delimiterStart + 1;
        }
        String tag = statement.substring(delimiterStart + 1, delimiterEnd);
        if (!tag.isEmpty() && !DOLLAR_QUOTE_TAG.matcher(tag).matches()) {
            return delimiterStart + 1;
        }
        String delimiter = statement.substring(delimiterStart, delimiterEnd + 1);
        int valueEnd = statement.indexOf(delimiter, delimiterEnd + 1);
        return valueEnd < 0 ? statement.length() : valueEnd + delimiter.length();
    }

    private boolean isSqlIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private String[] splitStatements(String sql) {
        if (sql == null || sql.isBlank()) {
            return new String[0];
        }
        List<String> statements = new ArrayList<>();
        int statementStart = 0;
        int parenthesisDepth = 0;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'' || current == '"') {
                index = skipQuoted(sql, index, current);
                continue;
            }
            if (current == '$') {
                int quotedEnd = skipDollarQuoted(sql, index);
                if (quotedEnd > index + 1) {
                    index = quotedEnd;
                    continue;
                }
            }
            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                index = skipLineComment(sql, index);
                continue;
            }
            if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                index = skipBlockComment(sql, index);
                continue;
            }
            if (current == '(') {
                parenthesisDepth++;
            } else if (current == ')') {
                parenthesisDepth = Math.max(0, parenthesisDepth - 1);
            } else if (current == ';' && parenthesisDepth == 0) {
                addStatement(statements, sql.substring(statementStart, index));
                statementStart = index + 1;
            }
            index++;
        }
        addStatement(statements, sql.substring(statementStart));
        return statements.toArray(String[]::new);
    }

    private void addStatement(List<String> statements, String candidate) {
        String stripped = candidate.strip();
        if (!stripped.isBlank()) {
            statements.add(stripped);
        }
    }

    private String normalize(String statement) {
        return stripComments(statement)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private String stripComments(String statement) {
        StringBuilder sanitized = new StringBuilder(statement.length());
        int index = 0;
        while (index < statement.length()) {
            char current = statement.charAt(index);
            if (current == '\'' || current == '"') {
                int quotedEnd = skipQuoted(statement, index, current);
                sanitized.append(statement, index, quotedEnd);
                index = quotedEnd;
                continue;
            }
            if (current == '$') {
                int quotedEnd = skipDollarQuoted(statement, index);
                if (quotedEnd > index + 1) {
                    sanitized.append(statement, index, quotedEnd);
                    index = quotedEnd;
                    continue;
                }
            }
            if (current == '-' && index + 1 < statement.length() && statement.charAt(index + 1) == '-') {
                sanitized.append(' ');
                index = skipLineComment(statement, index);
                continue;
            }
            if (current == '/' && index + 1 < statement.length() && statement.charAt(index + 1) == '*') {
                sanitized.append(' ');
                index = skipBlockComment(statement, index);
                continue;
            }
            sanitized.append(current);
            index++;
        }
        return sanitized.toString();
    }

    private int skipLineComment(String statement, int commentStart) {
        int index = commentStart + 2;
        while (index < statement.length()
                && statement.charAt(index) != '\r'
                && statement.charAt(index) != '\n') {
            index++;
        }
        return index;
    }

    private int skipBlockComment(String statement, int commentStart) {
        int depth = 1;
        int index = commentStart + 2;
        while (index < statement.length() && depth > 0) {
            if (index + 1 < statement.length()
                    && statement.charAt(index) == '/'
                    && statement.charAt(index + 1) == '*') {
                depth++;
                index += 2;
            } else if (index + 1 < statement.length()
                    && statement.charAt(index) == '*'
                    && statement.charAt(index + 1) == '/') {
                depth--;
                index += 2;
            } else {
                index++;
            }
        }
        return index;
    }

    private boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private SqlRisk max(SqlRisk left, SqlRisk right) {
        return severity(left) >= severity(right) ? left : right;
    }

    private int severity(SqlRisk risk) {
        return switch (risk) {
            case UNKNOWN -> 0;
            case READ -> 1;
            case DATA_WRITE -> 2;
            case SCHEMA_WRITE -> 3;
            case DANGEROUS -> 4;
        };
    }

    private record SqlToken(String value, int depth) {
    }
}
