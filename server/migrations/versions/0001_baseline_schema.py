"""Baseline: the schema as of 2026-10-08 (session app only, no ring tables).

A fresh database gets here with `alembic upgrade head`. A database that
predates Alembic (built by the old create_all) is stamped instead: see
docs/reference.md, "Database migrations".

Revision ID: 0001
Revises:
Create Date: 2026-10-08
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '0001'
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table('clinics',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('name', sa.String(length=120), nullable=False),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('name')
    )
    op.create_table('pending_registrations',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('email', sa.String(length=254), nullable=False),
    sa.Column('name', sa.String(length=120), nullable=False),
    sa.Column('role', sa.Enum('practitioner', 'patient', name='role'), nullable=False),
    sa.Column('password_hash', sa.String(length=255), nullable=False),
    sa.Column('code_hash', sa.String(length=255), nullable=False),
    sa.Column('attempts', sa.Integer(), nullable=False),
    sa.Column('expires_at', sa.DateTime(), nullable=False),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('pending_registrations', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_pending_registrations_email'), ['email'], unique=True)

    op.create_table('headbands',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('device_id', sa.String(length=64), nullable=False),
    sa.Column('clinic_id', sa.Integer(), nullable=False),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.ForeignKeyConstraint(['clinic_id'], ['clinics.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('headbands', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_headbands_device_id'), ['device_id'], unique=True)

    op.create_table('users',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('google_id', sa.String(length=64), nullable=True),
    sa.Column('email', sa.String(length=254), nullable=True),
    sa.Column('name', sa.String(length=120), nullable=False),
    sa.Column('role', sa.Enum('practitioner', 'patient', name='role'), nullable=False),
    sa.Column('password_hash', sa.String(length=255), nullable=True),
    sa.Column('clinic_id', sa.Integer(), nullable=True),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.ForeignKeyConstraint(['clinic_id'], ['clinics.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('google_id')
    )
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_users_email'), ['email'], unique=True)

    op.create_table('headband_assignments',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('headband_id', sa.Integer(), nullable=False),
    sa.Column('patient_id', sa.Integer(), nullable=False),
    sa.Column('practitioner_id', sa.Integer(), nullable=False),
    sa.Column('started_at', sa.DateTime(), nullable=False),
    sa.Column('ended_at', sa.DateTime(), nullable=True),
    sa.ForeignKeyConstraint(['headband_id'], ['headbands.id'], ),
    sa.ForeignKeyConstraint(['patient_id'], ['users.id'], ),
    sa.ForeignKeyConstraint(['practitioner_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('headband_assignments', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_headband_assignments_headband_id'), ['headband_id'], unique=False)
        batch_op.create_index(batch_op.f('ix_headband_assignments_patient_id'), ['patient_id'], unique=False)
        batch_op.create_index('uq_headband_active_assignment', ['headband_id'], unique=True, sqlite_where=sa.text('ended_at IS NULL'), postgresql_where=sa.text('ended_at IS NULL'))
        batch_op.create_index('uq_headband_patient_active_assignment', ['patient_id'], unique=True, sqlite_where=sa.text('ended_at IS NULL'), postgresql_where=sa.text('ended_at IS NULL'))

    op.create_table('patient_notes',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('patient_id', sa.Integer(), nullable=False),
    sa.Column('practitioner_id', sa.Integer(), nullable=False),
    sa.Column('body', sa.String(length=2000), nullable=False),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.Column('deleted_at', sa.DateTime(), nullable=True),
    sa.ForeignKeyConstraint(['patient_id'], ['users.id'], ),
    sa.ForeignKeyConstraint(['practitioner_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('patient_notes', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_patient_notes_patient_id'), ['patient_id'], unique=False)
        batch_op.create_index('ix_patient_notes_patient_practitioner', ['patient_id', 'practitioner_id'], unique=False)

    op.create_table('patient_roster',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('practitioner_id', sa.Integer(), nullable=False),
    sa.Column('patient_id', sa.Integer(), nullable=False),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.ForeignKeyConstraint(['patient_id'], ['users.id'], ),
    sa.ForeignKeyConstraint(['practitioner_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('practitioner_id', 'patient_id', name='uq_roster_practitioner_patient')
    )
    with op.batch_alter_table('patient_roster', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_patient_roster_patient_id'), ['patient_id'], unique=False)
        batch_op.create_index(batch_op.f('ix_patient_roster_practitioner_id'), ['practitioner_id'], unique=False)

    op.create_table('tcm_sessions',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('patient_id', sa.Integer(), nullable=False),
    sa.Column('practitioner_id', sa.Integer(), nullable=False),
    sa.Column('headband_id', sa.Integer(), nullable=True),
    sa.Column('modality', sa.String(length=32), nullable=False),
    sa.Column('started_at', sa.DateTime(), nullable=False),
    sa.Column('ended_at', sa.DateTime(), nullable=False),
    sa.Column('practitioner_note', sa.String(length=2000), nullable=True),
    sa.Column('patient_dozed', sa.Boolean(), nullable=True),
    sa.Column('alertness_rating', sa.Integer(), nullable=True),
    sa.Column('eeg_raw_path', sa.String(length=500), nullable=True),
    sa.Column('eeg_sample_rate_hz', sa.Float(), nullable=True),
    sa.Column('eeg_sample_count', sa.Integer(), nullable=True),
    sa.Column('eeg_analyzed_at', sa.DateTime(), nullable=True),
    sa.Column('eeg_interpolated_fraction', sa.Float(), nullable=True),
    sa.Column('eeg_excluded_channels', sa.String(length=64), nullable=True),
    sa.Column('eeg_electrode_findings', sa.JSON(), nullable=True),
    sa.Column('baseline_duration_seconds', sa.Integer(), nullable=True),
    sa.Column('treatment_end_seconds', sa.Integer(), nullable=True),
    sa.Column('client_session_id', sa.String(length=64), nullable=True),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.Column('deleted_at', sa.DateTime(), nullable=True),
    sa.ForeignKeyConstraint(['headband_id'], ['headbands.id'], ),
    sa.ForeignKeyConstraint(['patient_id'], ['users.id'], ),
    sa.ForeignKeyConstraint(['practitioner_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('tcm_sessions', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_tcm_sessions_patient_id'), ['patient_id'], unique=False)
        batch_op.create_index('uq_tcm_session_client_id', ['practitioner_id', 'client_session_id'], unique=True)

    op.create_table('session_eeg_metrics',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('session_id', sa.Integer(), nullable=False),
    sa.Column('half', sa.String(length=16), nullable=False),
    sa.Column('aperiodic_offset', sa.Float(), nullable=False),
    sa.Column('aperiodic_exponent', sa.Float(), nullable=False),
    sa.Column('r_squared', sa.Float(), nullable=False),
    sa.Column('epoch_count', sa.Integer(), nullable=False),
    sa.Column('eog_sem_rate_per_min', sa.Float(), nullable=True),
    sa.Column('eog_windows_analyzed', sa.Integer(), nullable=False),
    sa.Column('epochs_rejected_bad_contact', sa.Integer(), nullable=True),
    sa.Column('epochs_rejected_amplitude', sa.Integer(), nullable=True),
    sa.Column('epochs_partial_contact', sa.Integer(), nullable=True),
    sa.Column('spectrum', sa.JSON(), nullable=True),
    sa.Column('deflection_rate_per_min', sa.Float(), nullable=True),
    sa.ForeignKeyConstraint(['session_id'], ['tcm_sessions.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('session_id', 'half', name='uq_session_eeg_metric_half')
    )
    with op.batch_alter_table('session_eeg_metrics', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_session_eeg_metrics_session_id'), ['session_id'], unique=False)

    op.create_table('session_markers',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('session_id', sa.Integer(), nullable=False),
    sa.Column('offset_seconds', sa.Integer(), nullable=False),
    sa.Column('label', sa.String(length=200), nullable=False),
    sa.ForeignKeyConstraint(['session_id'], ['tcm_sessions.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('session_markers', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_session_markers_session_id'), ['session_id'], unique=False)

    op.create_table('session_oscillation_peaks',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('session_id', sa.Integer(), nullable=False),
    sa.Column('half', sa.String(length=16), nullable=False),
    sa.Column('band', sa.String(length=16), nullable=False),
    sa.Column('center_frequency', sa.Float(), nullable=False),
    sa.Column('power', sa.Float(), nullable=False),
    sa.Column('bandwidth', sa.Float(), nullable=False),
    sa.ForeignKeyConstraint(['session_id'], ['tcm_sessions.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('session_oscillation_peaks', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_session_oscillation_peaks_session_id'), ['session_id'], unique=False)

    op.create_table('session_samples',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('session_id', sa.Integer(), nullable=False),
    sa.Column('offset_seconds', sa.Integer(), nullable=False),
    sa.Column('band', sa.String(length=16), nullable=False),
    sa.Column('value', sa.Float(), nullable=False),
    sa.ForeignKeyConstraint(['session_id'], ['tcm_sessions.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('session_samples', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_session_samples_session_id'), ['session_id'], unique=False)
        batch_op.create_index('ix_session_samples_session_offset', ['session_id', 'offset_seconds'], unique=False)



def downgrade() -> None:
    with op.batch_alter_table('session_samples', schema=None) as batch_op:
        batch_op.drop_index('ix_session_samples_session_offset')
        batch_op.drop_index(batch_op.f('ix_session_samples_session_id'))

    op.drop_table('session_samples')
    with op.batch_alter_table('session_oscillation_peaks', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_session_oscillation_peaks_session_id'))

    op.drop_table('session_oscillation_peaks')
    with op.batch_alter_table('session_markers', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_session_markers_session_id'))

    op.drop_table('session_markers')
    with op.batch_alter_table('session_eeg_metrics', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_session_eeg_metrics_session_id'))

    op.drop_table('session_eeg_metrics')
    with op.batch_alter_table('tcm_sessions', schema=None) as batch_op:
        batch_op.drop_index('uq_tcm_session_client_id')
        batch_op.drop_index(batch_op.f('ix_tcm_sessions_patient_id'))

    op.drop_table('tcm_sessions')
    with op.batch_alter_table('patient_roster', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_patient_roster_practitioner_id'))
        batch_op.drop_index(batch_op.f('ix_patient_roster_patient_id'))

    op.drop_table('patient_roster')
    with op.batch_alter_table('patient_notes', schema=None) as batch_op:
        batch_op.drop_index('ix_patient_notes_patient_practitioner')
        batch_op.drop_index(batch_op.f('ix_patient_notes_patient_id'))

    op.drop_table('patient_notes')
    with op.batch_alter_table('headband_assignments', schema=None) as batch_op:
        batch_op.drop_index('uq_headband_patient_active_assignment', sqlite_where=sa.text('ended_at IS NULL'), postgresql_where=sa.text('ended_at IS NULL'))
        batch_op.drop_index('uq_headband_active_assignment', sqlite_where=sa.text('ended_at IS NULL'), postgresql_where=sa.text('ended_at IS NULL'))
        batch_op.drop_index(batch_op.f('ix_headband_assignments_patient_id'))
        batch_op.drop_index(batch_op.f('ix_headband_assignments_headband_id'))

    op.drop_table('headband_assignments')
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_users_email'))

    op.drop_table('users')
    with op.batch_alter_table('headbands', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_headbands_device_id'))

    op.drop_table('headbands')
    with op.batch_alter_table('pending_registrations', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_pending_registrations_email'))

    op.drop_table('pending_registrations')
    op.drop_table('clinics')
    sa.Enum(name='role').drop(op.get_bind(), checkfirst=True)
