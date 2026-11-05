"""Bring the database schema up to date with Alembic (server/migrations/).

Called on server start and by seed.py, so a fresh database and an existing
one both end up at the latest revision with no manual step.
"""
from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import Engine, inspect, text

from .database import engine

ALEMBIC_INI = Path(__file__).resolve().parent.parent / "alembic.ini"

# Any constant works; it only has to be the same in every server process.
_PG_MIGRATION_LOCK = 0x7C3E55


class UnversionedDatabaseError(RuntimeError):
    pass


def upgrade_to_head(bind: Engine = engine) -> None:
    config = Config(str(ALEMBIC_INI))
    config.attributes["configure_logger"] = False
    with bind.begin() as connection:
        if connection.dialect.name == "postgresql":
            # Cloud Run may start two copies at once; the second waits here
            # instead of racing the first through the same DDL.
            connection.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": _PG_MIGRATION_LOCK})
        tables = set(inspect(connection).get_table_names())
        if "alembic_version" not in tables and "users" in tables:
            # Built by the old create_all: Alembic would try to create every
            # table again. Stamping is a deliberate one-time step, because
            # an old database may have drifted from the models.
            raise UnversionedDatabaseError(
                "This database predates migrations. Back it up, check it with "
                "`python tools/schema_diff.py`, then run `alembic stamp 0001` "
                "from server/ and start the server again (see docs/reference.md, "
                "\"Database migrations\")."
            )
        config.attributes["connection"] = connection
        command.upgrade(config, "head")
