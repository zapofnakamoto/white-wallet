# Security policy

White Wallet handles money. Please report vulnerabilities privately, never in a public issue or pull request.

## Reporting

Use GitHub's private vulnerability reporting: **Security → Report a vulnerability** on
[zapofnakamoto/white-wallet](https://github.com/zapofnakamoto/white-wallet/security/advisories/new). Include what
is affected, how to reproduce it, and what an attacker could gain. You'll get an answer as soon as possible, and
credit in the advisory if you want it.

## Scope

- The wallet itself: key and seed handling, storage, the protocol implementation (`protocol/*`), and anything that
  could lose, leak or misdirect funds (see the money-safety rules in [docs/DESIGN.md](docs/DESIGN.md)).
- The build: anything that would make a release differ from its source.

Bugs in a mint or in the LNURLcash spec belong to their projects
([lnurlcash](https://github.com/lnurlcash), [lnurl/luds](https://github.com/lnurl/luds)); tell us too if the wallet
should guard against them.

Only the latest release is supported.
