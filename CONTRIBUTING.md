# Contributing

Setup is in the [README](README.md). Read [ARCHITECTURE.md](ARCHITECTURE.md)
first, especially its invariants, and check its decisions before changing
something that looks wrong: several are deliberate.

## Branches and pushes

- Branch from `main` for anything non-trivial, named by intent: `fix/…`,
  `feat/…`, `docs/…`. Trivial changes can go straight to `main`.
- **Never force-push or delete `main`.** GitHub doesn't enforce this on the
  repo's plan, so it's a rule everyone keeps. To undo something on `main`,
  `git revert` it. A genuine history rewrite (say, purging a leaked secret)
  needs the team's agreement first.

## Review before every push

Every push gets a review from someone, or something, that didn't write the
code: the unpushed diff (last pushed commit to `HEAD`), checked with fresh
eyes. Resolve each finding or explicitly decide to leave it, then push.

- **Working with Claude Code:** have it run a review subagent with a clean
  context, fed the diff, the project rules and a one-paragraph summary
  of the intent. The inline `/code-review` skill doesn't count, because it runs in
  the author's own context. `/code-review ultra` is a deeper multi-agent
  cloud review; it's billed and you start it yourself, so save it for
  high-stakes pushes.
- **Without Claude:** a teammate who didn't write it.

That review is the gate. Once its findings are handled, push directly;
there's no separate pull-request review on top of it.

## Commits

Imperative subject ("Fix cross-central connect", not "Fixed …"), a blank
line, then a body explaining **why**. `git log` has plenty of examples.

Comments in code explain why the code is the way it is. History ("switched
from X", dates, "caught in review") goes in the commit message instead.

## Tests

**Server**, before any server push (CI runs the same on every push that
touches `server/` or `testdata/`):

```sh
cd server
.venv/bin/pip install -r requirements-dev.txt   # once
.venv/bin/ruff check .
.venv/bin/python -m pytest
```

They fake Claude and AssemblyAI and blank the real keys, so they're free and
offline. Add a test with every server fix.

**Android** (needs the Muse SDK in place, so local only):

```sh
cd android/TcmSessionApp
./gradlew testDebugUnitTest assembleDebug
```

**iOS** has no test target yet; build it in Xcode.

## Schema changes

Change `server/app/models.py`, run
`alembic revision --autogenerate -m "..."` from `server/`, and **read the
generated file**: autogenerate misses renames and never moves data. Keep
`tests/test_migrations.py` green. Details:
[`docs/reference.md`](docs/reference.md#database-migrations). Back up
`smartring.db` to `server/backups/` before any destructive change to a
database you care about.
