package ai.nubase.mcp.safety;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlRiskClassifierTest {

    private final SqlRiskClassifier classifier = new SqlRiskClassifier();

    @Test
    void classifiesReadQueries() {
        assertThat(classifier.classify("select * from todos")).isEqualTo(SqlRisk.READ);
        assertThat(classifier.classify("with recent as (select * from todos) select * from recent"))
                .isEqualTo(SqlRisk.READ);
        assertThat(classifier.classify("select E'a\\\\b'")).isEqualTo(SqlRisk.READ);
    }

    @Test
    void classifiesSchemaWrites() {
        assertThat(classifier.classify("create table todos (id bigserial primary key)"))
                .isEqualTo(SqlRisk.SCHEMA_WRITE);
        assertThat(classifier.classify("alter table todos add column done boolean"))
                .isEqualTo(SqlRisk.SCHEMA_WRITE);
        assertThat(classifier.classify(
                "alter table if exists public.todos add column if not exists done boolean not null"))
                .isEqualTo(SqlRisk.SCHEMA_WRITE);
    }

    @Test
    void classifiesDataWrites() {
        assertThat(classifier.classify("insert into todos(text) values ('ship')"))
                .isEqualTo(SqlRisk.DATA_WRITE);
        assertThat(classifier.classify("update todos set done = true"))
                .isEqualTo(SqlRisk.DATA_WRITE);
        assertThat(classifier.classify(
                "with changed as (update todos set done = true returning *) select * from changed"))
                .isEqualTo(SqlRisk.DATA_WRITE);
    }

    @Test
    void classifiesDeleteWithTopLevelWhereAsDataWrite() {
        List.of(
                "delete from todos where id = 1",
                "delete from public.todos as t where t.id = 1",
                "delete from public.todos t where t.id = 1",
                "delete from only public.todos * where id = 1",
                "delete from only (public.todos) * as t where current of todo_cursor",
                "delete from todos using archived_todos a where todos.id = a.id"
        ).forEach(sql -> assertThat(classifier.classify(sql))
                .as("risk for %s", sql)
                .isEqualTo(SqlRisk.DATA_WRITE));
    }

    @Test
    void preservesWithDeleteDataWriteContract() {
        assertThat(classifier.classify(
                "with ids as (select id from todos) delete from todos where id in (select id from ids)"))
                .isEqualTo(SqlRisk.DATA_WRITE);
        assertThat(classifier.classify(
                "with changed as (delete from todos where id = 1 returning *) select * from changed"))
                .isEqualTo(SqlRisk.DATA_WRITE);
    }

    @Test
    void classifiesDangerousStatements() {
        assertThat(classifier.classify("drop table todos")).isEqualTo(SqlRisk.DANGEROUS);
        assertThat(classifier.classify("truncate table todos")).isEqualTo(SqlRisk.DANGEROUS);
        assertThat(classifier.classify("delete from todos")).isEqualTo(SqlRisk.DANGEROUS);
        assertThat(classifier.classify("copy todos to program 'external-command'"))
                .isEqualTo(SqlRisk.DANGEROUS);
        assertThat(classifier.classify("grant all on todos to app_user"))
                .isEqualTo(SqlRisk.DANGEROUS);
    }

    @Test
    void ignoresSemicolonsInsideSqlLexicalScopes() {
        List.of(
                "copy public.todos /* ; select harmless */ to program $$external-command$$",
                "copy \"x; select harmless\" to program $$external-command$$",
                "copy (select $$x; select harmless$$) to program $$external-command$$",
                "copy (select 'x''; select harmless') to program $$external-command$$",
                "copy (select E'x\\'; select harmless') to program $$external-command$$",
                "copy (select $Body$x; select harmless$Body$) to program $Command$external-command$Command$",
                "alter table public.todos /* ; select harmless */ owner to app_owner",
                "alter table \"x; select harmless\" owner to app_owner",
                "alter table \"x\"\"; select harmless\" owner to app_owner"
        ).forEach(sql -> assertThat(classifier.classify(sql))
                .as("risk for %s", sql)
                .isEqualTo(SqlRisk.DANGEROUS));
    }

    @Test
    void classifiesUnboundedPostgresDeleteVariantsAsDangerous() {
        List.of(
                "delete from public.todos as t",
                "delete from public.todos t",
                "delete from only public.todos",
                "delete from only (public.todos)",
                "delete from only (public.todos) * as t",
                "delete from public.todos * returning id",
                "delete from public.todos using archived_todos a",
                "delete from public.todos using (select id from archived_todos where archived) a",
                "delete from \"where\" returning 'where'",
                "with source as (select id from archived_todos where archived) "
                        + "delete from todos using source",
                "with changed as (delete from todos returning *) select * from changed"
        ).forEach(sql -> assertThat(classifier.classify(sql))
                .as("risk for %s", sql)
                .isEqualTo(SqlRisk.DANGEROUS));
    }

    @Test
    void ignoresWhereKeywordsInsidePostgresComments() {
        List.of(
                "delete from todos /* where false */",
                "delete from todos -- where false\n",
                "delete from todos /* outer /* inner */ where false */",
                "delete from todos /* outer /* where false */ still comment */ returning id"
        ).forEach(sql -> assertThat(classifier.classify(sql))
                .as("risk for %s", sql)
                .isEqualTo(SqlRisk.DANGEROUS));
    }

    @Test
    void ignoresDeleteAndAlterKeywordsInsideComments() {
        List.of(
                "select 1 /* delete from todos */",
                "select 1 -- alter role app_user bypassrls\n",
                "select 1 /* outer /* delete from todos */ alter database app_db */"
        ).forEach(sql -> assertThat(classifier.classify(sql))
                .as("risk for %s", sql)
                .isEqualTo(SqlRisk.READ));
    }

    @Test
    void classifiesPrivilegedIdentityAndDatabaseOperationsAsDangerous() {
        List.of(
                "alter role app_user bypassrls",
                "alter user app_user set search_path to public",
                "alter group app_admins add user app_user",
                "alter database app_db allow_connections false",
                "alter default privileges grant all on tables to app_user",
                "create database app_db",
                "create group app_admins",
                "create tablespace app_space location '/var/lib/postgresql/app-space'",
                "alter tablespace app_space owner to app_owner",
                "alter table todos owner to app_owner",
                "reassign owned by old_owner to app_owner"
        ).forEach(sql -> assertThat(classifier.classify(sql))
                .as("risk for %s", sql)
                .isEqualTo(SqlRisk.DANGEROUS));
    }

    @Test
    void classifiesExecutableOrEncapsulatingDdlAsDangerous() {
        List.of(
                "create function emit_event() returns void language plpgsql "
                        + "as $$ begin perform pg_notify('events', 'created;drop table todos'); end $$",
                "create or replace function emit_event() returns void language sql "
                        + "as $body$ select pg_notify('events', 'created') $body$",
                "create procedure rebuild() language plpgsql as $$ begin truncate table todos; end $$",
                "create trigger todos_audit after insert on todos execute function emit_event()",
                "create rule todos_notify as on insert to todos do also select emit_event()",
                "create view active_todos as select emit_event(), * from todos",
                "create materialized view active_todos as select emit_event(), * from todos",
                "create table copied_todos as select emit_event(), * from todos",
                "create extension untrusted_extension",
                "create index todos_expression_idx on todos ((emit_event()))",
                "alter function emit_event() owner to app_owner",
                "alter procedure rebuild() rename to rebuild_all",
                "alter trigger todos_audit on todos rename to todos_audit_v2",
                "alter rule todos_notify on todos rename to todos_notify_v2"
        ).forEach(sql -> assertThat(classifier.classify(sql))
                .as("risk for %s", sql)
                .isEqualTo(SqlRisk.DANGEROUS));
    }

    @Test
    void classifiesDestructiveOrExecutableAlterTableAsDangerous() {
        List.of(
                "alter table todos drop column done",
                "alter table todos detach partition todos_2025",
                "alter table todos owner to app_owner",
                "alter table todos rename to archived_todos",
                "alter table todos set schema archive",
                "alter table todos alter column done drop default",
                "alter table todos add constraint todos_check check (emit_event())",
                "alter table todos add column event_id bigint default emit_event()",
                "alter table todos add column payload text default $$drop table todos;$$",
                "alter table todos add column first_flag boolean, add column second_flag boolean",
                "alter table todos add column done boolean, drop column legacy"
        ).forEach(sql -> assertThat(classifier.classify(sql))
                .as("risk for %s", sql)
                .isEqualTo(SqlRisk.DANGEROUS));
    }

    @Test
    void mixedStatementsReturnHighestRisk() {
        assertThat(classifier.classify("select * from todos; drop table todos;"))
                .isEqualTo(SqlRisk.DANGEROUS);
        assertThat(classifier.classify("select * from todos; create table notes(id bigint);"))
                .isEqualTo(SqlRisk.SCHEMA_WRITE);
        assertThat(classifier.classify("select 1 -- harmless\r; drop table victims"))
                .isEqualTo(SqlRisk.DANGEROUS);
    }

    @Test
    void plainStringsWithBackslashesAreUnknown() {
        assertThat(classifier.classify("SELECT 'a\\'; SELECT '; DROP TABLE victims; --'"))
                .isEqualTo(SqlRisk.UNKNOWN);
        assertThat(classifier.classify("select 'plain\\path'"))
                .isEqualTo(SqlRisk.UNKNOWN);
    }

    @Test
    void countsNonBlankStatements() {
        assertThat(classifier.countStatements("select 1; ; select 2;")).isEqualTo(2);
        assertThat(classifier.countStatements("select ';'; select 2")).isEqualTo(2);
        assertThat(classifier.countStatements("select \"x; select harmless\"; select 2")).isEqualTo(2);
        assertThat(classifier.countStatements("select $Body$x; select harmless$Body$; select 2"))
                .isEqualTo(2);
        assertThat(classifier.countStatements("select 1 /* outer ; /* inner ; */ comment */; select 2"))
                .isEqualTo(2);
        assertThat(classifier.countStatements("select 1 -- ; select harmless\n; select 2"))
                .isEqualTo(2);
        assertThat(classifier.countStatements("select 1 -- ; select harmless\r; select 2"))
                .isEqualTo(2);
        assertThat(classifier.countStatements(null)).isZero();
    }

    @Test
    void blankSqlIsUnknown() {
        assertThat(classifier.classify(" ")).isEqualTo(SqlRisk.UNKNOWN);
    }

    @Test
    void unknownStatementMakesTheEntireBatchUnknown() {
        assertThat(classifier.classify("select 1; listen app_events"))
                .isEqualTo(SqlRisk.UNKNOWN);
    }
}
