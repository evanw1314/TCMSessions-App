"""Reports tables, columns, indexes and unique constraints the models declare
but a live DB doesn't have.

Its job is the one-time check before stamping a database that predates
Alembic (docs/reference.md, "Database migrations"). Such a database was
built by the old `create_all`, which created missing tables but never
ALTERed an existing one, so it may be behind the models in ways Alembic's
baseline can't see: stamping only records "this is revision 0001", it
checks nothing. (A dev database once turned out to be seven columns behind
on `tcm_sessions`, and every raw-EEG call against it had been failing
unnoticed.) Once a database is versioned, `alembic check`
does this job.

    cd server && .venv/bin/python tools/schema_diff.py   # .venv\\Scripts\\python.exe on Windows

It chdirs to `server/` itself, so it is safe to invoke by absolute path from
anywhere — without that, both `.env` and a relative `sqlite:///./smartring.db`
resolve against the caller's directory, and the tool would cheerfully inspect
(and CREATE) an empty stray database and report no drift at all.

DELIBERATELY REPORT-ONLY. It prints the `ALTER TABLE` / `CREATE INDEX`
statements it believes are needed and stops; you apply them by hand, with the
server stopped, against a backed-up database. The only queries it runs are
reads: reflection, and the duplicate counts described below. Back up first,
always:

    mkdir -p backups && cp smartring.db backups/smartring-$(date +%Y%m%d-%H%M).db

INDEXES AND UNIQUE CONSTRAINTS. `create_all(checkfirst=True)`
skips an existing table *including its indexes*, so an index or UNIQUE
constraint added to an already-created table is never applied. That is the
same silent drift as a missing column, but quieter still, because nothing ever
errors: a missing `uq_headband_active_assignment` /
`uq_headband_patient_active_assignment` lets a headband be actively assigned
twice (the one-active-headband invariant).

- A model index counts as present if the DB has an index of the same name;
  or, under any other name, one with the same columns IN THE SAME ORDER, the
  same uniqueness and the same partial WHERE; or — for a unique, non-partial
  model index — a unique constraint over the same SET of columns.
- A same-named index whose columns, uniqueness or WHERE differ goes under
  NEEDS CARE, since fixing it means a DROP. When the model's version is
  unique, existing rows are checked first (below): the DROP is suggested only
  if the CREATE after it would succeed, because dropping first and then
  failing the CREATE would leave the table with no index at all.
- A UNIQUE constraint counts as present if the DB has a unique constraint, or
  a non-partial unique index, over the same set of columns.
- A missing UNIQUE constraint is emitted as `CREATE UNIQUE INDEX`, because
  SQLite cannot add a constraint to an existing table without rebuilding it.
  A unique index enforces exactly the same rule, and the previous point means
  this tool accepts it on the next run, so following its advice converges.
  Unnamed constraints (a column's `unique=True`) get `uq_<table>_<columns>`.
- Before suggesting ANY unique index it counts rows that already break it,
  because the CREATE fails outright if there are any, and a nonzero count
  means the invariant is already broken in the data, which matters more than
  the missing index. Rows with a NULL in any key column are left out of the
  count, matching how SQLite and Postgres both treat NULLs in a unique index.
  A unique index that would fail goes under NEEDS CARE with a query listing
  the offending rows, never as a plain statement. A count that itself errors
  is reported there too, rather than ending the run with a traceback.

WHAT IT DOES NOT CATCH — read this before trusting a clean run:

- **Changed column types**, which SQLite's dynamic typing makes unreliable
  enough to compare that a wrong answer would be worse than none.
- **Columns or indexes the DB has and the models don't** — harmless, so not
  reported.
- **Index details beyond name, columns, uniqueness and partial WHERE**: sort
  order, collation, index method. WHERE clauses are compared as normalized
  text (case, whitespace and outer parentheses ignored), so a logically equal
  clause written differently would be reported as differing. Check by eye
  before dropping anything. Partial WHERE clauses are expected as `text()`,
  the way models.py writes them. One built from column expressions renders
  table-qualified, so it would compare as differing; if it also compares
  against a value, the bind parameter makes its duplicate count fail
  (reported, not crashed on). A false "differs" on clean rows suggests a
  DROP + CREATE that just rebuilds the same index — churn, not damage.
- **Foreign keys and CHECK constraints.** `Enum(...)` compiles to a plain
  `VARCHAR(n)` here, dropping the CHECK constraint `create_all` would have
  written on a fresh table.
- **Postgres is untested.** The index checks were verified against SQLite
  only; the reflection calls are dialect-neutral, but run it against a
  throwaway Postgres branch before trusting it there.

A NOT NULL column cannot be added bare to a table that already has rows. Where
the model carries a static default, the emitted statement includes
`NOT NULL DEFAULT <literal>` (matching the hand-written migration notes in
models.py). Where the default is computed in Python — `default=utcnow`, say —
no valid single statement exists, so the column is reported under NEEDS CARE
without a statement rather than as a bare nullable ADD COLUMN that would leave
NULLs the model swears cannot be there.
"""

import os
import sys
from pathlib import Path

# Both `.env` (config.Settings) and a relative sqlite URL resolve against the
# CURRENT directory, so pin it to server/ before importing anything that reads
# them. Must happen before the app imports below, hence the E402s.
SERVER_DIR = Path(__file__).resolve().parent.parent
os.chdir(SERVER_DIR)
sys.path.insert(0, str(SERVER_DIR))

from sqlalchemy import inspect, text  # noqa: E402
from sqlalchemy.engine import Inspector  # noqa: E402
from sqlalchemy.exc import SQLAlchemyError  # noqa: E402
from sqlalchemy.schema import Column, CreateIndex, Index, Table, UniqueConstraint  # noqa: E402

from app import models  # noqa: E402,F401  (imported for the side effect: registers every table on Base.metadata)
from app.database import Base, engine  # noqa: E402

# "sqlite" or "postgresql" — also the prefix of the `<dialect>_where` keyword
# a model Index uses to declare a partial index (see models.HeadbandAssignment).
DIALECT = engine.dialect.name
_quote = engine.dialect.identifier_preparer.quote


def _static_default(column: Column) -> str | None:
    """The column's default rendered as a SQL literal, or None if there isn't
    one that DDL can express. A SQLAlchemy `default=` is applied in PYTHON on
    insert, so it is invisible to the database — which is exactly the trap
    this function exists to handle: `default=0` does NOT make an added column
    zero for rows that already exist, only `DEFAULT 0` in the DDL does. A
    callable default (`default=utcnow`) has no literal form at all."""
    default = column.default
    if default is None or not getattr(default, "is_scalar", False):
        return None
    value = default.arg
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return repr(value)
    if isinstance(value, str):
        escaped = value.replace("'", "''")
        return f"'{escaped}'"
    return None


def _wrapped_in_parens(s: str) -> bool:
    """True when the first "(" closes at the very last character — so
    "(a IS NULL)" is wrapped but "(a) AND (b)" is not."""
    if not (s.startswith("(") and s.endswith(")")):
        return False
    depth = 0
    for i, ch in enumerate(s):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0 and i != len(s) - 1:
                return False
    return True


def _where(options) -> str | None:
    """A partial index's WHERE clause as comparable text, or None for a full
    index. `options` is a model Index's dialect_kwargs or a reflected index's
    dialect_options; both key the clause as `<dialect>_where`. SQLite
    reflects it as a TextClause and Postgres as a string wrapped in
    parentheses, so both are lowercased, whitespace-collapsed and stripped of
    outer parentheses before comparing."""
    clause = (options or {}).get(f"{DIALECT}_where")
    if clause is None:
        return None
    s = " ".join(str(clause).split()).lower()
    while _wrapped_in_parens(s):
        s = s[1:-1].strip()
    return s or None


def _model_where_sql(ix: Index) -> str | None:
    """The model index's WHERE clause as SQL for the duplicate count — as
    written, not normalized."""
    clause = ix.dialect_kwargs.get(f"{DIALECT}_where")
    return None if clause is None else str(clause)


def _columns(reflected: dict) -> list[str] | None:
    """A reflected index's column names, or None for an expression index
    (reflection puts None where an expression stands). An expression index
    never matches a model index here; this app declares none."""
    names = reflected.get("column_names") or []
    return None if any(n is None for n in names) else list(names)


def _enforces_unique(cols: list[str], db_indexes: list[dict], db_uniques: list[dict]) -> bool:
    """Whether the DB already rejects duplicate rows over this set of columns,
    by a unique constraint or a non-partial unique index. A partial one
    doesn't count: it only enforces uniqueness on the rows its WHERE admits."""
    want = sorted(cols)
    if any(sorted(u["column_names"]) == want for u in db_uniques):
        return True
    return any(
        ix["unique"] and _where(ix.get("dialect_options")) is None and sorted(_columns(ix) or []) == want
        for ix in db_indexes
    )


def _duplicate_listing(table_name: str, cols: list[str], where_sql: str | None) -> str:
    """A query listing the key values that more than one row shares — the
    rows a unique index over `cols` (restricted by `where_sql`, if partial)
    would reject. Rows with a NULL key column are excluded: SQLite and
    Postgres both let NULLs repeat in a unique index."""
    key = ", ".join(_quote(c) for c in cols)
    conditions = [f"{_quote(c)} IS NOT NULL" for c in cols]
    if where_sql:
        conditions.append(f"({where_sql})")
    return (
        f"SELECT {key}, COUNT(*) AS n FROM {_quote(table_name)} "
        f"WHERE {' AND '.join(conditions)} GROUP BY {key} HAVING COUNT(*) > 1"
    )


def _violations(listing: str) -> int | str:
    """How many sets of rows `listing` finds, or the error text if the count
    itself fails. Each count gets its own short-lived connection, so one
    failure can't leave a shared transaction aborted (Postgres) and take every
    later check down with it. Read-only either way."""
    try:
        with engine.connect() as conn:
            return conn.execute(text(f"SELECT COUNT(*) FROM ({listing}) AS dup")).scalar_one()
    except SQLAlchemyError as e:
        return f"{type(e).__name__}: {str(e).splitlines()[0]}"


def _violation_note(label: str, cols: list[str], found: int | str, listing: str) -> str:
    if isinstance(found, str):
        return (
            f"{label}: couldn't check existing rows against this unique index ({found}). "
            f"Run the check by hand before creating it:\n    {listing};"
        )
    return (
        f"{label}: {found} set(s) of rows already share the same ({', '.join(cols)}), so the "
        f"unique index can't be created, and the rule it enforces is already broken in the data. "
        f"Resolve those rows by hand, then re-run. List them with:\n    {listing};"
    )


def _add_unique_index(
    label: str,
    table_name: str,
    cols: list[str],
    where_sql: str | None,
    ddl: str,
    statements: list[str],
    needs_care: list[str],
) -> None:
    """Emits `ddl` as a plain statement only if no existing rows would make
    it fail; otherwise reports why under NEEDS CARE."""
    listing = _duplicate_listing(table_name, cols, where_sql)
    found = _violations(listing)
    if found == 0:
        statements.append(ddl)
    else:
        needs_care.append(_violation_note(label, cols, found, listing))


def _index_drift(
    inspector: Inspector, table: Table, present: set[str], statements: list[str], needs_care: list[str]
) -> None:
    """Index and unique-constraint drift for one existing table — see the
    module docstring's INDEXES section for the matching rules."""
    db_indexes = inspector.get_indexes(table.name)
    db_uniques = inspector.get_unique_constraints(table.name)
    db_by_name = {ix["name"]: ix for ix in db_indexes if ix.get("name")}

    for ix in sorted(table.indexes, key=lambda i: i.name or ""):
        label = f"{table.name}.{ix.name}"
        cols = [c.name for c in ix.columns]
        where = _where(ix.dialect_kwargs)
        ddl = f"{CreateIndex(ix).compile(dialect=engine.dialect)};"
        if any(c not in present for c in cols):
            needs_care.append(f"{label} is on column(s) the DB doesn't have yet — add them first, then re-run.")
            continue

        found = db_by_name.get(ix.name)
        if found is not None:
            diffs = []
            if bool(found["unique"]) != bool(ix.unique):
                diffs.append(f"unique={bool(found['unique'])} in the DB but {bool(ix.unique)} in the model")
            if _columns(found) != cols:
                diffs.append(f"columns {_columns(found)} in the DB but {cols} in the model")
            found_where = _where(found.get("dialect_options"))
            if found_where != where:
                diffs.append(f"WHERE {found_where or '(none)'} in the DB but {where or '(none)'} in the model")
            if not diffs:
                continue
            note = f"{label} exists but differs: {'; '.join(diffs)}."
            listing = _duplicate_listing(table.name, cols, _model_where_sql(ix)) if ix.unique else None
            violations = _violations(listing) if listing else 0
            if violations == 0:
                fits = " Existing rows fit the model's version, so with" if ix.unique else " With"
                note += f"{fits} the server stopped, replace it:\n    DROP INDEX {_quote(ix.name)}; {ddl}"
            else:
                # Don't suggest the DROP: the CREATE after it would fail and
                # leave the table with no index at all.
                note += (
                    " Do NOT drop it yet — "
                    + _violation_note(label, cols, violations, listing).split(": ", 1)[1]
                )
            needs_care.append(note)
            continue

        # The same index under another name (e.g. created by hand) is present.
        if any(
            _columns(d) == cols and _where(d.get("dialect_options")) == where and bool(d["unique"]) == bool(ix.unique)
            for d in db_indexes
        ):
            continue
        if ix.unique and where is None and _enforces_unique(cols, db_indexes, db_uniques):
            continue

        if ix.unique:
            _add_unique_index(label, table.name, cols, _model_where_sql(ix), ddl, statements, needs_care)
        else:
            statements.append(ddl)

    uniques = [c for c in table.constraints if isinstance(c, UniqueConstraint)]
    for uc in sorted(uniques, key=lambda c: (c.name or "", [col.name for col in c.columns])):
        cols = [c.name for c in uc.columns]
        # Unnamed constraints (a column's unique=True) need a name to become an
        # index; this one is stable, and a re-run recognizes it by columns anyway.
        name = uc.name or f"uq_{table.name}_{'_'.join(cols)}"
        label = f"{table.name}.{name}"
        if any(c not in present for c in cols):
            needs_care.append(f"{label} is on column(s) the DB doesn't have yet — add them first, then re-run.")
            continue
        if _enforces_unique(cols, db_indexes, db_uniques):
            continue
        ddl = (
            f"CREATE UNIQUE INDEX {_quote(name)} ON {_quote(table.name)} "
            f"({', '.join(_quote(c) for c in cols)});  -- stands in for the model's UNIQUE constraint"
        )
        _add_unique_index(label, table.name, cols, None, ddl, statements, needs_care)


def main() -> int:
    if engine.url.drivername.startswith("sqlite"):
        db_path = engine.url.database
        # Not a bare existence check for tidiness: connecting to a missing
        # sqlite path CREATES it, and the tool would then inspect a brand-new
        # empty file and report a clean bill of health for a database that
        # was never opened. Refuse instead.
        if db_path and db_path not in (":memory:", "") and not Path(db_path).exists():
            print(f"No database at {Path(db_path).resolve()} (relative to {SERVER_DIR}).")
            print("Refusing to create one — point DATABASE_URL at the real database, or start the server first.")
            return 2

    inspector = inspect(engine)
    existing_tables = set(inspector.get_table_names())

    missing_tables: list[str] = []
    statements: list[str] = []
    index_statements: list[str] = []
    needs_care: list[str] = []

    for table in Base.metadata.sorted_tables:
        if table.name not in existing_tables:
            # Stamping 0001 won't create a missing table. The simplest fix is
            # usually to let the 0001 migration build a fresh database and
            # copy the data across; report it and let a person decide.
            missing_tables.append(table.name)
            continue

        present = {column["name"] for column in inspector.get_columns(table.name)}
        for column in table.columns:
            if column.name in present:
                continue
            ddl_type = column.type.compile(engine.dialect)
            if column.nullable:
                statements.append(f"ALTER TABLE {table.name} ADD COLUMN {column.name} {ddl_type};")
                continue

            literal = _static_default(column)
            if literal is not None:
                statements.append(
                    f"ALTER TABLE {table.name} ADD COLUMN {column.name} {ddl_type} NOT NULL DEFAULT {literal};"
                )
            else:
                needs_care.append(
                    f"{table.name}.{column.name} ({ddl_type}) is NOT NULL with no static default — "
                    f"needs a backfill value chosen by hand, or a table rebuild. No statement emitted."
                )

        _index_drift(inspector, table, present, index_statements, needs_care)

    print(f"Database: {engine.url}")
    print(f"Missing tables (create these by hand before stamping): {missing_tables or 'none'}")
    print()

    if not statements and not index_statements and not needs_care:
        if missing_tables:
            print("-- no column or index drift, but tables are missing (see above)")
        else:
            print("-- none: every model column, index and unique constraint exists in the database")
        # Non-zero on missing tables too: a database missing tables is not a
        # healthy one to report green, even though the fix is automatic.
        return 1 if missing_tables else 0

    if statements or index_statements:
        print("-- Back up the database BEFORE running these (see this file's docstring) --")
    if statements:
        print("\n".join(statements))
    if index_statements:
        if statements:
            print()
        print("-- Indexes and unique constraints (unique ones already checked: no existing rows break them) --")
        print("\n".join(index_statements))

    if needs_care:
        print()
        print("-- NEEDS CARE: no single safe statement exists for these --")
        print("\n".join(needs_care))

    # Non-zero so a future CI step or pre-flight check can fail on drift.
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
