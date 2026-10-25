# Changelog

Notable changes to White Wallet, newest first ([Keep a Changelog](https://keepachangelog.com/)). A `v*` tag
publishes its section as the GitHub release notes, which brands that follow White Wallet's releases pick up.

## [Unreleased]

## [0.1.0]

First public version.

- Setup with a preselected mint and Lightning Address name; restore from a recovery phrase.
- Receive with a Lightning Address or an invoice for a set amount; claim notes from links, QR codes and NFC tags.
- Pay Lightning invoices, addresses and LNURL-pay links; hand out a note as a link or QR code, and take it back.
- Several accounts, each with its own recovery phrase; notes on several mints, collected at the home mint
  automatically; trusted mints.
- Note strategies (keep one note, or manage notes by hand in the Notes tab).
- Encrypted storage, optional app lock, English and German.
- White-labeling by configuration (`brand/`, `-PbrandDir`, `dev/new-brand.sh`) and brand code through
  `core/brand-api`.
