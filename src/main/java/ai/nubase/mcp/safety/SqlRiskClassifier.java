package ai.nubase.mcp.safety;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class SqlRiskClassifier {

    private static final Set<String> READ_WORDS = Set.of("select", "with", "show", "describe", "values");
    private static final Set<String> DATA_WRITE_WORDS = Set.of("insert", "update", "merge", "copy", "call");
    private static final Set<String> SCHEMA_WRITE_WORDS = Set.of("create", "alter", "grant", "revoke", "comment");
    private static final Set<String> DANGEROUS_WORDS = Set.of("drop", "truncate", "reindex", "cluster");
    private static final Set<String> CONTROL_WORDS = Set.of(
            "begin", "start", "commit", "end", "rollback", "savepoint", "release", "set", "reset");
    private static final Set<String> EXPLAIN_COMMANDS = Set.of(
            "select", "insert", "update", "delete", "merge", "with", "execute", "values", "create");

    public SqlAnalysis analyze(String sql) {
        List<List<Token>> statements = scanStatements(sql);
        if (statements.isEmpty()) {
            return new SqlAnalysis(SqlRisk.UNKNOWN, 0, false);
        }

        SqlRisk risk = SqlRisk.UNKNOWN;
        boolean hasUnknown = false;
        for (List<Token> statement : statements) {
            SqlRisk statementRisk = classifyStatement(statement);
            if (statementRisk == SqlRisk.UNKNOWN) {
                hasUnknown = true;
            }
            risk = max(risk, statementRisk);
        }
        return new SqlAnalysis(risk, statements.size(), hasUnknown);
    }

    public SqlRisk classify(String sql) {
        return analyze(sql).risk();
    }

    public int countStatements(String sql) {
        return analyze(sql).statementCount();
    }

    private SqlRisk classifyStatement(List<Token> tokens) {
        int firstWordIndex = firstWordIndex(tokens);
        if (firstWordIndex < 0) {
            return SqlRisk.UNKNOWN;
        }

        String firstWord = tokens.get(firstWordIndex).value();
        if (firstWord.equals("prepare") || firstWord.equals("execute")) {
            return SqlRisk.UNKNOWN;
        }
        if (firstWord.equals("do")) {
            return SqlRisk.DANGEROUS;
        }
        if (firstWord.equals("explain")) {
            return classifyExplain(tokens, firstWordIndex);
        }
        if (firstWord.equals("with")) {
            return classifyWith(tokens, firstWordIndex);
        }
        if (firstWord.equals("set")) {
            return classifySet(tokens, firstWordIndex);
        }
        if (CONTROL_WORDS.contains(firstWord)) {
            return SqlRisk.READ;
        }
        if (firstWord.equals("analyze") || firstWord.equals("refresh") || firstWord.equals("lock")) {
            return SqlRisk.DATA_WRITE;
        }
        if (firstWord.equals("vacuum")) {
            return "full".equals(nextWord(tokens, firstWordIndex)) ? SqlRisk.DANGEROUS : SqlRisk.DATA_WRITE;
        }
        if (firstWord.equals("copy")) {
            return hasWordSequenceAtDepth(tokens, List.of("from", "program"), tokens.get(firstWordIndex).depth())
                            || hasWordSequenceAtDepth(tokens, List.of("to", "program"), tokens.get(firstWordIndex).depth())
                    ? SqlRisk.DANGEROUS
                    : SqlRisk.DATA_WRITE;
        }
        if (firstWord.equals("delete")) {
            if (!"from".equals(nextWordAtDepth(tokens, firstWordIndex))) {
                return SqlRisk.UNKNOWN;
            }
            return deleteHasWhere(tokens, firstWordIndex) ? SqlRisk.DATA_WRITE : SqlRisk.DANGEROUS;
        }
        if (firstWord.equals("security") && "label".equals(nextWordAtDepth(tokens, firstWordIndex))) {
            return SqlRisk.SCHEMA_WRITE;
        }
        if (DANGEROUS_WORDS.contains(firstWord)) {
            return SqlRisk.DANGEROUS;
        }
        if (SCHEMA_WRITE_WORDS.contains(firstWord)) {
            return SqlRisk.SCHEMA_WRITE;
        }
        if (DATA_WRITE_WORDS.contains(firstWord)) {
            return SqlRisk.DATA_WRITE;
        }
        if (READ_WORDS.contains(firstWord)) {
            return SqlRisk.READ;
        }
        return SqlRisk.UNKNOWN;
    }

    private SqlRisk classifyExplain(List<Token> tokens, int explainIndex) {
        int commandIndex = -1;
        for (int index = explainIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.word()
                    && token.depth() == tokens.get(explainIndex).depth()
                    && EXPLAIN_COMMANDS.contains(token.value())) {
                commandIndex = index;
                break;
            }
        }
        if (commandIndex < 0) {
            return SqlRisk.UNKNOWN;
        }

        int analyzeIndex = -1;
        for (int index = explainIndex + 1; index < commandIndex; index++) {
            Token token = tokens.get(index);
            if (token.word() && token.value().equals("analyze")) {
                analyzeIndex = index;
                break;
            }
        }
        String analyzeValue = analyzeIndex < 0 ? null : nextWord(tokens, analyzeIndex);
        if (analyzeIndex < 0 || "false".equals(analyzeValue) || "off".equals(analyzeValue)) {
            return SqlRisk.READ;
        }

        return classifyStatement(tokens.subList(commandIndex, tokens.size()));
    }

    private SqlRisk classifyWith(List<Token> tokens, int withIndex) {
        int rootDepth = tokens.get(withIndex).depth();
        SqlRisk risk = SqlRisk.UNKNOWN;
        int index = withIndex + 1;

        while (index < tokens.size()) {
            int asIndex = findWordAtDepth(tokens, "as", rootDepth, index);
            if (asIndex < 0) {
                break;
            }
            int openIndex = findTokenAtDepth(tokens, "(", rootDepth, asIndex + 1);
            if (openIndex < 0) {
                break;
            }
            int closeIndex = matchingCloseIndex(tokens, openIndex, rootDepth);
            if (closeIndex < 0) {
                break;
            }

            risk = max(risk, classifyStatement(tokens.subList(openIndex + 1, closeIndex)));
            index = closeIndex + 1;

            int separatorIndex = nextSignificantIndex(tokens, index);
            if (separatorIndex < 0) {
                return risk;
            }
            if (tokens.get(separatorIndex).value().equals(",")) {
                index = separatorIndex + 1;
                continue;
            }
            return max(risk, classifyStatement(tokens.subList(separatorIndex, tokens.size())));
        }
        return risk;
    }

    private SqlRisk classifySet(List<Token> tokens, int setIndex) {
        String next = nextWordAtDepth(tokens, setIndex);
        if ("role".equals(next)) {
            return SqlRisk.DANGEROUS;
        }
        if ("session".equals(next) || "local".equals(next)) {
            Integer modifierIndex = nextWordIndexAtDepth(tokens, setIndex);
            String operation = modifierIndex == null ? null : nextWordAtDepth(tokens, modifierIndex);
            if ("role".equals(operation) || "authorization".equals(operation)) {
                return SqlRisk.DANGEROUS;
            }
        }
        return SqlRisk.READ;
    }

    private boolean deleteHasWhere(List<Token> tokens, int deleteIndex) {
        int deleteDepth = tokens.get(deleteIndex).depth();
        for (int index = deleteIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.value().equals(")") && token.depth() < deleteDepth) {
                return false;
            }
            if (token.depth() != deleteDepth || !token.word()) {
                continue;
            }
            if (token.value().equals("where")) {
                return true;
            }
            if (token.value().equals("returning")) {
                return false;
            }
        }
        return false;
    }

    private int firstWordIndex(List<Token> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).word()) {
                return index;
            }
        }
        return -1;
    }

    private String nextWord(List<Token> tokens, int fromIndex) {
        for (int index = fromIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.word()) {
                return token.value();
            }
        }
        return null;
    }

    private String nextWordAtDepth(List<Token> tokens, int fromIndex) {
        Integer index = nextWordIndexAtDepth(tokens, fromIndex);
        return index == null ? null : tokens.get(index).value();
    }

    private Integer nextWordIndexAtDepth(List<Token> tokens, int fromIndex) {
        int depth = tokens.get(fromIndex).depth();
        for (int index = fromIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.word() && token.depth() == depth) {
                return index;
            }
        }
        return null;
    }

    private boolean hasWordSequenceAtDepth(List<Token> tokens, List<String> values, int depth) {
        List<String> words = tokens.stream()
                .filter(token -> token.word() && token.depth() == depth)
                .map(Token::value)
                .toList();
        for (int index = 0; index + values.size() <= words.size(); index++) {
            if (words.subList(index, index + values.size()).equals(values)) {
                return true;
            }
        }
        return false;
    }

    private int findWordAtDepth(List<Token> tokens, String value, int depth, int fromIndex) {
        for (int index = fromIndex; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.word() && token.depth() == depth && token.value().equals(value)) {
                return index;
            }
        }
        return -1;
    }

    private int findTokenAtDepth(List<Token> tokens, String value, int depth, int fromIndex) {
        for (int index = fromIndex; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.depth() == depth && token.value().equals(value)) {
                return index;
            }
        }
        return -1;
    }

    private int matchingCloseIndex(List<Token> tokens, int openIndex, int depth) {
        for (int index = openIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.depth() == depth && token.value().equals(")")) {
                return index;
            }
        }
        return -1;
    }

    private int nextSignificantIndex(List<Token> tokens, int fromIndex) {
        for (int index = fromIndex; index < tokens.size(); index++) {
            if (!tokens.get(index).value().equals("?")) {
                return index;
            }
        }
        return -1;
    }

    private List<List<Token>> scanStatements(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }

        List<List<Token>> statements = new ArrayList<>();
        List<Token> tokens = new ArrayList<>();
        int depth = 0;
        int index = 0;

        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = charAt(sql, index + 1);

            if (Character.isWhitespace(current)) {
                index++;
            } else if (current == '-' && next == '-') {
                index = consumeLineComment(sql, index + 2);
            } else if (current == '/' && next == '*') {
                index = consumeBlockComment(sql, index + 2);
            } else if ((current == 'e' || current == 'E') && next == '\'' && !isWordChar(charAt(sql, index - 1))) {
                tokens.add(Token.opaque(depth));
                index = consumeSingleQuote(sql, index + 1, true);
            } else if (current == '\'') {
                tokens.add(Token.opaque(depth));
                index = consumeSingleQuote(sql, index, false);
            } else if (current == '"') {
                tokens.add(Token.opaque(depth));
                index = consumeDoubleQuote(sql, index);
            } else if (current == '$' && dollarDelimiterAt(sql, index) != null) {
                String delimiter = dollarDelimiterAt(sql, index);
                tokens.add(Token.opaque(depth));
                index = consumeDollarQuote(sql, index, delimiter);
            } else if (current == ';') {
                if (!tokens.isEmpty()) {
                    statements.add(tokens);
                }
                tokens = new ArrayList<>();
                depth = 0;
                index++;
            } else if (current == '(') {
                tokens.add(new Token("(", depth, false));
                depth++;
                index++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
                tokens.add(new Token(")", depth, false));
                index++;
            } else if (isWordStart(current)) {
                int end = index + 1;
                while (end < sql.length() && isWordChar(sql.charAt(end))) {
                    end++;
                }
                tokens.add(new Token(sql.substring(index, end).toLowerCase(Locale.ROOT), depth, true));
                index = end;
            } else {
                tokens.add(new Token(String.valueOf(current), depth, false));
                index++;
            }
        }

        if (!tokens.isEmpty()) {
            statements.add(tokens);
        }
        return statements;
    }

    private int consumeLineComment(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
            index++;
        }
        return index;
    }

    private int consumeBlockComment(String sql, int index) {
        int nesting = 1;
        while (index < sql.length() && nesting > 0) {
            if (charAt(sql, index) == '/' && charAt(sql, index + 1) == '*') {
                nesting++;
                index += 2;
            } else if (charAt(sql, index) == '*' && charAt(sql, index + 1) == '/') {
                nesting--;
                index += 2;
            } else {
                index++;
            }
        }
        return index;
    }

    private int consumeSingleQuote(String sql, int quoteIndex, boolean escapeBackslash) {
        int index = quoteIndex + 1;
        while (index < sql.length()) {
            if (escapeBackslash && sql.charAt(index) == '\\') {
                index = Math.min(sql.length(), index + 2);
            } else if (sql.charAt(index) == '\'' && charAt(sql, index + 1) == '\'') {
                index += 2;
            } else if (sql.charAt(index) == '\'') {
                return index + 1;
            } else {
                index++;
            }
        }
        return index;
    }

    private int consumeDoubleQuote(String sql, int quoteIndex) {
        int index = quoteIndex + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == '"' && charAt(sql, index + 1) == '"') {
                index += 2;
            } else if (sql.charAt(index) == '"') {
                return index + 1;
            } else {
                index++;
            }
        }
        return index;
    }

    private String dollarDelimiterAt(String sql, int index) {
        int end = sql.indexOf('$', index + 1);
        if (end < 0) {
            return null;
        }
        String tag = sql.substring(index + 1, end);
        if (tag.isEmpty() || tag.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return sql.substring(index, end + 1);
        }
        return null;
    }

    private int consumeDollarQuote(String sql, int index, String delimiter) {
        int end = sql.indexOf(delimiter, index + delimiter.length());
        return end < 0 ? sql.length() : end + delimiter.length();
    }

    private char charAt(String value, int index) {
        return index < 0 || index >= value.length() ? '\0' : value.charAt(index);
    }

    private boolean isWordStart(char value) {
        return value == '_' || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private boolean isWordChar(char value) {
        return isWordStart(value) || value >= '0' && value <= '9' || value == '$';
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

    private record Token(String value, int depth, boolean word) {
        private static Token opaque(int depth) {
            return new Token("?", depth, false);
        }
    }
}
