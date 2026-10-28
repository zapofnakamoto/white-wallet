# Contributing

Thanks for helping. Issues and pull requests are welcome; for anything larger than a fix, open an issue first so we
can agree on the approach. Security issues are reported privately: see [SECURITY.md](SECURITY.md).

## Setup

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for requirements, tests and the local regtest stack, and
[docs/DESIGN.md](docs/DESIGN.md) for the principles and money-safety rules every change must keep.

Before opening a pull request, run:

```sh
just check
```

## Commit messages

Commits follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <summary in the imperative, lower case>

<optional body: what and why>
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`. Scopes name the area, e.g. `wallet`,
`protocol`, `send`, `claim`, `notes`, `settings`, `brand`, `regtest`. Mark breaking changes with `!`
(`feat(brand)!: …`) and explain them in the body.

A change users or brands will notice also adds a line to `CHANGELOG.md` under `## [Unreleased]`. A release renames
that section to the version and tags `v<version>`; CI turns the section into the GitHub release notes.

## Project rules

- **Every text works in other languages.** Use plural resources, whole sentences with placeholders, and no English
  built in Kotlin. A new string needs its German translation too; lint fails without one.
- **The words for the custodian and its notes** ("mint", "note") appear only in the strings marked *Brand terms*.
- **No brand values outside `brand/`**: no app name, application ID, mint host or brand color in the code
  (`dev/check-brand.sh`).
- **Brand-specific behavior** is a brand option or goes through `core/brand-api` / `core/brand-ui`, not a fork of the wallet. Screens use the designsystem's components, not Material's (`dev/check-design.sh`).
- **Protocol behavior is verified on regtest.** A wallet change that talks to a mint (claims, payments, transfers,
  rotate/merge/split/melt, lookups, reconciliation, fees, addresses, restore) is finished only once a test against
  the local regtest stack verifies it: a `Regtest*` test, run with `just test-regtest`. For a bug, that test
  reproduces it first and fails without the fix. Mock-mint and unit tests are welcome on top, never instead. See
  [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#tests).
- **Updates keep users' data.** Since v0.3.0, a schema change comes with a Room migration and a test that
  upgrades a database of the previous version (`MigrationTest`); a renamed preference key, a stored enum value or
  format keeps being read.
- **Developers may get breaking changes, users never.** Only what is on an installed app's phone is kept working
  across updates. Everything developers work with may break in any release: `brand.properties` options, string keys,
  `core/brand-api` and `core/brand-ui`, design components, the `dev/` scripts, `just` recipes and the build. Such a
  change needs no alias or deprecation, only a line in CHANGELOG.md that says what to change, and `!` in its commit.

## Brands

Changes for your own brand belong in your brand directory or repository, not here (see
[docs/WHITE_LABEL.md](docs/WHITE_LABEL.md)). A pull request is the right place for an option or extension point
that other brands can use too.
