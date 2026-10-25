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
- **The word for the custodian** ("mint") appears only in the strings marked *Brand term*.
- **No brand values outside `brand/`**: no app name, application ID, mint host or brand color in the code
  (`dev/check-brand.sh`).
- **Brand-specific behavior** is a brand option or goes through `core/brand-api`, not a fork of the wallet.
- **No migrations yet.** Before the first release, schema changes start the database empty; there is no
  backward compatibility to keep.

## Brands

Changes for your own brand belong in your brand directory or repository, not here (see
[docs/WHITE_LABEL.md](docs/WHITE_LABEL.md)). A pull request is the right place for an option or extension point
that other brands can use too.
