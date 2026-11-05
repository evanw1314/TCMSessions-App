from sqlalchemy import Float, create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import settings

connect_args = {"check_same_thread": False} if settings.database_url.startswith("sqlite") else {}
# pool_pre_ping: a hosted Postgres (free tiers especially) can
# close idle connections server-side; without this, the next request on a
# stale pooled connection fails outright instead of transparently
# reconnecting. No-op cost for SQLite, so left on unconditionally.
# At most 10 connections per server process on Postgres: Cloud SQL's
# smallest tier allows about 25 in all, and Cloud Run runs up to 2 copies
# (docs/cloud-deploy.md). SQLite keeps SQLAlchemy's defaults.
pool_args = {} if settings.database_url.startswith("sqlite") else {"pool_size": 5, "max_overflow": 5}
engine = create_engine(settings.database_url, connect_args=connect_args, pool_pre_ping=True, **pool_args)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


class Base(DeclarativeBase):
    # SQLAlchemy 2.1 maps a bare `float` to Double where 2.0 used Float; pin
    # it so the models keep matching the migrations on either version.
    type_annotation_map = {float: Float}


def get_db():
    db: Session = SessionLocal()
    try:
        yield db
    finally:
        db.close()
