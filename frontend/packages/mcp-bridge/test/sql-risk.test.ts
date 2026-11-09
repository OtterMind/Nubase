import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import * as sqlRisk from "../src/sql-risk.js";

type RiskCase = {
  name: string;
  sql: string;
  risk: string;
  statementCount: number;
  hasUnknown: boolean;
};

const cases = JSON.parse(
  await readFile(
    new URL(
      "../../../../../test-fixtures/sql-risk-cases.json",
      import.meta.url,
    ),
    "utf8",
  ),
) as RiskCase[];

test("matches the shared SQL risk and statement-boundary contract", () => {
  for (const fixture of cases) {
    assert.equal(
      sqlRisk.classifySql(fixture.sql),
      fixture.risk,
      `${fixture.name}: risk`,
    );
    assert.equal(
      sqlRisk.countStatements(fixture.sql),
      fixture.statementCount,
      `${fixture.name}: statement count`,
    );
  }
});

test("reports unknown statements independently of the highest known risk", () => {
  const analyzeSql = (
    sqlRisk as unknown as {
      analyzeSql?: (sql: string) => {
        risk: string;
        statementCount: number;
        hasUnknown: boolean;
      };
    }
  ).analyzeSql;

  assert.equal(
    typeof analyzeSql,
    "function",
    "sql-risk must export analyzeSql",
  );
  for (const fixture of cases) {
    assert.deepEqual(
      analyzeSql?.(fixture.sql),
      {
        risk: fixture.risk,
        statementCount: fixture.statementCount,
        hasUnknown: fixture.hasUnknown,
      },
      fixture.name,
    );
  }
});
