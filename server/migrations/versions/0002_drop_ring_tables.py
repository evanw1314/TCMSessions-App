"""Drop the smart-ring tables left behind by the ring app's removal.

Databases built before 2026-10-08 still hold rings, ring_assignments,
measurements and notifications; the code stopped using them when the ring
app was archived (tag archive/ring-app). A database created from the 0001
baseline never had them, so each drop is conditional.

There is no downgrade: the data is gone, and restoring the ring app means
restoring its models from the archive tag.

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-08
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import context, op

revision: str = '0002'
down_revision: Union[str, Sequence[str], None] = '0001'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

# Children before parents, so no foreign key blocks a drop.
RING_TABLES = ("measurements", "ring_assignments", "notifications", "rings")


def upgrade() -> None:
    if context.is_offline_mode():
        # `alembic upgrade --sql` has no database to inspect.
        for table in RING_TABLES:
            op.execute(f"DROP TABLE IF EXISTS {table}")
        return
    existing = set(sa.inspect(op.get_bind()).get_table_names())
    for table in RING_TABLES:
        if table in existing:
            op.drop_table(table)


def downgrade() -> None:
    raise NotImplementedError("The ring tables are not recreated; restore them from tag archive/ring-app.")
