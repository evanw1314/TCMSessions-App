"""Alembic migrations (server/migrations/, app/migrate.py).

The test suite's own database is built by create_all for speed, so these
check separately that the migrations produce exactly the models' schema,
and that a database from before migrations is refused until stamped.
"""
import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from sqlalchemy import create_engine, inspect, text

from app.database import Base
from app.migrate import ALEMBIC_INI, UnversionedDatabaseError, upgrade_to_head


@pytest.fixture
def scratch_engine(tmp_path):
    eng = create_engine(f"sqlite:///{(tmp_path / 'scratch.db').as_posix()}")
    yield eng
    eng.dispose()


def test_migrations_match_the_models(scratch_engine):
    upgrade_to_head(scratch_engine)
    with scratch_engine.connect() as conn:
        diff = compare_metadata(MigrationContext.configure(conn), Base.metadata)
    assert diff == []


def test_a_pre_migration_database_is_refused_then_upgraded_once_stamped(scratch_engine):
    # What create_all built before Alembic, including a leftover ring table.
    Base.metadata.create_all(scratch_engine)
    with scratch_engine.begin() as conn:
        conn.execute(text("CREATE TABLE rings (id INTEGER PRIMARY KEY, device_id VARCHAR(64))"))
        conn.execute(text("INSERT INTO clinics (name) VALUES ('Kept Clinic')"))

    with pytest.raises(UnversionedDatabaseError):
        upgrade_to_head(scratch_engine)

    config = Config(str(ALEMBIC_INI))
    config.attributes["configure_logger"] = False
    with scratch_engine.begin() as conn:
        config.attributes["connection"] = conn
        command.stamp(config, "0001")
    upgrade_to_head(scratch_engine)

    with scratch_engine.connect() as conn:
        assert "rings" not in inspect(conn).get_table_names()
        assert conn.execute(text("SELECT name FROM clinics")).scalar_one() == "Kept Clinic"
