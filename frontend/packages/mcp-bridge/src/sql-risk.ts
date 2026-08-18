export type SqlRisk =
  | "UNKNOWN"
  | "READ"
  | "DATA_WRITE"
  | "SCHEMA_WRITE"
  | "DANGEROUS";

export type SqlAnalysis = {
  risk: SqlRisk;
  statementCount: number;
  hasUnknown: boolean;
};

type Token = {
  value: string;
  depth: number;
  word: boolean;
};

const READ_WORDS = new Set(["select", "with", "show", "describe", "values"]);
const DATA_WRITE_WORDS = new Set(["insert", "update", "merge", "copy", "call"]);
const SCHEMA_WRITE_WORDS = new Set([
  "create",
  "alter",
  "grant",
  "revoke",
  "comment",
]);
const DANGEROUS_WORDS = new Set(["drop", "truncate", "reindex", "cluster"]);
const CONTROL_WORDS = new Set([
  "begin",
  "start",
  "commit",
  "end",
  "rollback",
  "savepoint",
  "release",
  "set",
  "reset",
]);
const EXPLAIN_COMMANDS = new Set([
  "select",
  "insert",
  "update",
  "delete",
  "merge",
  "with",
  "execute",
  "values",
  "create",
]);

export function analyzeSql(sql: string | undefined): SqlAnalysis {
  const statements = scanStatements(sql);
  if (statements.length === 0) {
    return { risk: "UNKNOWN", statementCount: 0, hasUnknown: false };
  }

  let risk: SqlRisk = "UNKNOWN";
  let hasUnknown = false;
  for (const statement of statements) {
    const statementRisk = classifyStatement(statement);
    if (statementRisk === "UNKNOWN") hasUnknown = true;
    risk = maxRisk(risk, statementRisk);
  }
  return { risk, statementCount: statements.length, hasUnknown };
}

export function classifySql(sql: string | undefined): SqlRisk {
  return analyzeSql(sql).risk;
}

export function countStatements(sql: string | undefined): number {
  return analyzeSql(sql).statementCount;
}

function classifyStatement(tokens: Token[]): SqlRisk {
  const firstWordIndex = tokens.findIndex((token) => token.word);
  if (firstWordIndex < 0) return "UNKNOWN";

  const firstWord = tokens[firstWordIndex]!.value;
  if (firstWord === "prepare" || firstWord === "execute") return "UNKNOWN";
  if (firstWord === "do") return "DANGEROUS";
  if (firstWord === "explain") return classifyExplain(tokens, firstWordIndex);
  if (firstWord === "with") return classifyWith(tokens, firstWordIndex);
  if (firstWord === "set") return classifySet(tokens, firstWordIndex);
  if (CONTROL_WORDS.has(firstWord)) return "READ";
  if (
    firstWord === "analyze" ||
    firstWord === "refresh" ||
    firstWord === "lock"
  )
    return "DATA_WRITE";
  if (firstWord === "vacuum")
    return nextWord(tokens, firstWordIndex) === "full"
      ? "DANGEROUS"
      : "DATA_WRITE";
  if (firstWord === "copy")
    return hasWordSequenceAtDepth(
      tokens,
      ["from", "program"],
      tokens[firstWordIndex]!.depth,
    ) ||
      hasWordSequenceAtDepth(
        tokens,
        ["to", "program"],
        tokens[firstWordIndex]!.depth,
      )
      ? "DANGEROUS"
      : "DATA_WRITE";
  if (firstWord === "delete") {
    if (nextWordAtDepth(tokens, firstWordIndex) !== "from") return "UNKNOWN";
    return deleteHasWhere(tokens, firstWordIndex)
      ? "DATA_WRITE"
      : "DANGEROUS";
  }
  if (firstWord === "security" && nextWordAtDepth(tokens, firstWordIndex) === "label")
    return "SCHEMA_WRITE";
  if (DANGEROUS_WORDS.has(firstWord)) return "DANGEROUS";
  if (SCHEMA_WRITE_WORDS.has(firstWord)) return "SCHEMA_WRITE";
  if (DATA_WRITE_WORDS.has(firstWord)) return "DATA_WRITE";
  if (firstWord === "select")
    return findWordAtDepth(
      tokens,
      "into",
      tokens[firstWordIndex]!.depth,
      firstWordIndex + 1,
    ) >= 0
      ? "SCHEMA_WRITE"
      : "READ";
  if (READ_WORDS.has(firstWord)) return "READ";
  return "UNKNOWN";
}

function classifyExplain(tokens: Token[], explainIndex: number): SqlRisk {
  const commandIndex = tokens.findIndex(
    (token, index) =>
      index > explainIndex &&
      token.word &&
      token.depth === tokens[explainIndex]!.depth &&
      EXPLAIN_COMMANDS.has(token.value),
  );
  if (commandIndex < 0) return "UNKNOWN";

  const analyzeIndex = tokens.findIndex(
    (token, index) =>
      index > explainIndex &&
      index < commandIndex &&
      token.word &&
      token.value === "analyze",
  );
  if (
    analyzeIndex < 0 ||
    ["false", "off"].includes(nextWord(tokens, analyzeIndex) ?? "")
  )
    return "READ";

  return classifyStatement(tokens.slice(commandIndex));
}

function classifyWith(tokens: Token[], withIndex: number): SqlRisk {
  const rootDepth = tokens[withIndex]!.depth;
  let risk: SqlRisk = "UNKNOWN";
  let index = withIndex + 1;

  while (index < tokens.length) {
    const asIndex = findWordAtDepth(tokens, "as", rootDepth, index);
    if (asIndex < 0) break;
    const openIndex = findTokenAtDepth(tokens, "(", rootDepth, asIndex + 1);
    if (openIndex < 0) break;
    const closeIndex = matchingCloseIndex(tokens, openIndex, rootDepth);
    if (closeIndex < 0) break;

    risk = maxRisk(risk, classifyStatement(tokens.slice(openIndex + 1, closeIndex)));
    index = closeIndex + 1;

    const separatorIndex = nextSignificantIndex(tokens, index);
    if (separatorIndex < 0) return risk;
    if (tokens[separatorIndex]!.value === ",") {
      index = separatorIndex + 1;
      continue;
    }
    return maxRisk(risk, classifyStatement(tokens.slice(separatorIndex)));
  }
  return risk;
}

function classifySet(tokens: Token[], setIndex: number): SqlRisk {
  const next = nextWordAtDepth(tokens, setIndex);
  if (next === "role") return "DANGEROUS";
  if (next === "session" || next === "local") {
    const modifierIndex = nextWordIndexAtDepth(tokens, setIndex);
    if (
      modifierIndex !== undefined &&
      ["role", "authorization"].includes(
        nextWordAtDepth(tokens, modifierIndex) ?? "",
      )
    )
      return "DANGEROUS";
  }
  return "READ";
}

function deleteHasWhere(tokens: Token[], deleteIndex: number): boolean {
  const deleteDepth = tokens[deleteIndex]!.depth;
  for (let index = deleteIndex + 1; index < tokens.length; index += 1) {
    const token = tokens[index]!;
    if (token.value === ")" && token.depth < deleteDepth) return false;
    if (token.depth !== deleteDepth || !token.word) continue;
    if (token.value === "where") return true;
    if (token.value === "returning") return false;
  }
  return false;
}

function nextWord(tokens: Token[], fromIndex: number): string | undefined {
  for (let index = fromIndex + 1; index < tokens.length; index += 1) {
    const token = tokens[index]!;
    if (token.word) return token.value;
  }
  return undefined;
}

function nextWordAtDepth(tokens: Token[], fromIndex: number): string | undefined {
  const index = nextWordIndexAtDepth(tokens, fromIndex);
  return index === undefined ? undefined : tokens[index]!.value;
}

function nextWordIndexAtDepth(
  tokens: Token[],
  fromIndex: number,
): number | undefined {
  const depth = tokens[fromIndex]!.depth;
  for (let index = fromIndex + 1; index < tokens.length; index += 1) {
    const token = tokens[index]!;
    if (token.word && token.depth === depth) return index;
  }
  return undefined;
}

function hasWordSequenceAtDepth(
  tokens: Token[],
  values: string[],
  depth: number,
): boolean {
  const words = tokens
    .filter((token) => token.word && token.depth === depth)
    .map((token) => token.value);
  return words.some((_, index) =>
    values.every((value, offset) => words[index + offset] === value),
  );
}

function findWordAtDepth(
  tokens: Token[],
  value: string,
  depth: number,
  fromIndex: number,
): number {
  return tokens.findIndex(
    (token, index) =>
      index >= fromIndex &&
      token.word &&
      token.depth === depth &&
      token.value === value,
  );
}

function findTokenAtDepth(
  tokens: Token[],
  value: string,
  depth: number,
  fromIndex: number,
): number {
  return tokens.findIndex(
    (token, index) =>
      index >= fromIndex && token.depth === depth && token.value === value,
  );
}

function matchingCloseIndex(
  tokens: Token[],
  openIndex: number,
  depth: number,
): number {
  return tokens.findIndex(
    (token, index) =>
      index > openIndex && token.depth === depth && token.value === ")",
  );
}

function nextSignificantIndex(tokens: Token[], fromIndex: number): number {
  for (let index = fromIndex; index < tokens.length; index += 1) {
    if (tokens[index]!.value !== "?") return index;
  }
  return -1;
}

function scanStatements(sql: string | undefined): Token[][] {
  if (!sql || !sql.trim()) return [];

  const statements: Token[][] = [];
  let tokens: Token[] = [];
  let depth = 0;
  let index = 0;

  const pushOpaque = () => tokens.push({ value: "?", depth, word: false });
  const finishStatement = () => {
    if (tokens.length > 0) statements.push(tokens);
    tokens = [];
    depth = 0;
  };

  while (index < sql.length) {
    const char = sql.charAt(index);
    const next = sql.charAt(index + 1);

    if (/\s/.test(char)) {
      index += 1;
      continue;
    }
    if (char === "-" && next === "-") {
      index = consumeLineComment(sql, index + 2);
      continue;
    }
    if (char === "/" && next === "*") {
      index = consumeBlockComment(sql, index + 2);
      continue;
    }
    if (
      (char === "e" || char === "E") &&
      next === "'" &&
      !isWordChar(sql.charAt(index - 1))
    ) {
      pushOpaque();
      index = consumeSingleQuote(sql, index + 1, true);
      continue;
    }
    if (char === "'") {
      pushOpaque();
      index = consumeSingleQuote(sql, index, false);
      continue;
    }
    if (char === '"') {
      pushOpaque();
      index = consumeDoubleQuote(sql, index);
      continue;
    }
    if (char === "$") {
      const delimiter = dollarDelimiterAt(sql, index);
      if (delimiter) {
        pushOpaque();
        index = consumeDollarQuote(sql, index, delimiter);
        continue;
      }
    }
    if (char === ";") {
      finishStatement();
      index += 1;
      continue;
    }
    if (char === "(") {
      tokens.push({ value: char, depth, word: false });
      depth += 1;
      index += 1;
      continue;
    }
    if (char === ")") {
      depth = Math.max(0, depth - 1);
      tokens.push({ value: char, depth, word: false });
      index += 1;
      continue;
    }
    if (isWordStart(char)) {
      let end = index + 1;
      while (end < sql.length && isWordChar(sql[end])) end += 1;
      tokens.push({
        value: sql.slice(index, end).toLowerCase(),
        depth,
        word: true,
      });
      index = end;
      continue;
    }

    tokens.push({ value: char, depth, word: false });
    index += 1;
  }

  finishStatement();
  return statements;
}

function consumeLineComment(sql: string, index: number): number {
  while (index < sql.length && sql[index] !== "\n" && sql[index] !== "\r")
    index += 1;
  return index;
}

function consumeBlockComment(sql: string, index: number): number {
  let nesting = 1;
  while (index < sql.length && nesting > 0) {
    if (sql[index] === "/" && sql[index + 1] === "*") {
      nesting += 1;
      index += 2;
    } else if (sql[index] === "*" && sql[index + 1] === "/") {
      nesting -= 1;
      index += 2;
    } else {
      index += 1;
    }
  }
  return index;
}

function consumeSingleQuote(
  sql: string,
  quoteIndex: number,
  escapeBackslash: boolean,
): number {
  let index = quoteIndex + 1;
  while (index < sql.length) {
    if (escapeBackslash && sql[index] === "\\") {
      index += Math.min(2, sql.length - index);
    } else if (sql[index] === "'" && sql[index + 1] === "'") {
      index += 2;
    } else if (sql[index] === "'") {
      return index + 1;
    } else {
      index += 1;
    }
  }
  return index;
}

function consumeDoubleQuote(sql: string, quoteIndex: number): number {
  let index = quoteIndex + 1;
  while (index < sql.length) {
    if (sql[index] === '"' && sql[index + 1] === '"') {
      index += 2;
    } else if (sql[index] === '"') {
      return index + 1;
    } else {
      index += 1;
    }
  }
  return index;
}

function dollarDelimiterAt(sql: string, index: number): string | undefined {
  const rest = sql.slice(index);
  return rest.match(/^\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$/)?.[0];
}

function consumeDollarQuote(
  sql: string,
  index: number,
  delimiter: string,
): number {
  const end = sql.indexOf(delimiter, index + delimiter.length);
  return end < 0 ? sql.length : end + delimiter.length;
}

function isWordStart(char: string | undefined): boolean {
  return Boolean(char && /[A-Za-z_]/.test(char));
}

function isWordChar(char: string | undefined): boolean {
  return Boolean(char && /[A-Za-z0-9_$]/.test(char));
}

function maxRisk(left: SqlRisk, right: SqlRisk): SqlRisk {
  return severity(left) >= severity(right) ? left : right;
}

function severity(risk: SqlRisk): number {
  return {
    UNKNOWN: 0,
    READ: 1,
    DATA_WRITE: 2,
    SCHEMA_WRITE: 3,
    DANGEROUS: 4,
  }[risk];
}
