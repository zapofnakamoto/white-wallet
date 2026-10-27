# Changelog

Notable changes to White Wallet, newest first ([Keep a Changelog](https://keepachangelog.com/)). A `v*` tag
publishes its section as the GitHub release notes, which brands that follow White Wallet's releases pick up.

## [Unreleased]

### Added

- An empty wallet says how money gets in: a line under the ₿0 balance (not while the balance is hidden). Brands can
  reword it (`home_wallet_empty`).
- With `allowServiceChange=true`, users can change their home mint from the mint pages (with `showMints=true`):
  "Change home mint" on the home mint's page, "Make this my home mint" on another one's. Their name there becomes the
  primary address, invoices are paid there from then on and, if the account collects at its home mint, its money
  follows. The old address keeps working and the old home stays trusted. `Wallet.changeHome`.
- Three new brand-term strings for it: `onboarding_change_service_title`, `service_change_home`, `service_make_home`.
  Brands with their own word for the mint override them too.

## [0.3.0]

### Added

- An alpha or beta release says so: a badge beside the app's name (or the brand's wordmark) at setup and on the
  wallet tab, and by the version in About, with a sentence on what it means at setup and in About. The brand's
  `releaseStage` decides; brands can reword it through strings and restyle the badge (`ReleaseBadge`).

### Changed

- `releaseStage` defaults to `auto`, which is beta below 1.0.0: a brand whose `versionName` is below 1.0.0 now shows
  the beta badge, unless it sets `releaseStage=stable`.
- Built with Gradle 9.8.2 and Kotlin 2.4.21, with DataStore 1.3.0, CameraX 1.6.2, OkHttp 5.5.1 and
  kotlinx.serialization 1.12.0.

## [0.2.0]

### Added

- Brands can design the app through tokens and code: colour roles, fonts, text styles, corners and spacing from
  brand resources, appearance in `brand.properties`, their own components and decorations at anchors, wallet events,
  and brand pages in Settings, the wallet menu or after setup (`core/brand-ui`, design levels in
  `docs/WHITE_LABEL.md`; `brand-examples/acme` uses every level). Without a brand design the app looks as before.

- Sending ends on a success screen like receiving does: the brand's picture, the amount, who was paid and the fee.
  By default both show a check mark that draws itself; brands replace it at the anchors `SendSuccess` and
  `ReceiveSuccess`.

- Setup spells out the whole Lightning Address below the name field.

- History shows the fees a mint pays back when notes are combined.

- In developer mode, debug builds with an account on a local mint can receive from the regtest faucet and fill in a
  regtest invoice on Send, to try both flows without a terminal.

- History shows an icon for each kind of entry, marks what the wallet did by itself ("Automatic") and what the user
  did, and can be narrowed to received, sent, moved and note maintenance. Brands choose the filters
  (`historyFilter`) and can restyle the icons (`ActivityIcon`, or the `ic_activity_*` drawables).

- A mint that presents a different signing key can be used again: its details page shows the remembered and the new
  key, and after a warning the new key takes the old one's place. The notes held there get the mint's certificates
  under the new key, so a note shared afterwards is verified for its recipient.

### Fixed

- A note handed out as a link shows as taken in History once it is claimed, and claiming one's own link takes it
  back instead of counting as received.
- A payment to a user on the same mint that was dropped is no longer shown as paid.
- Nothing is paid, split, combined or renewed through a mint that presents a different signing key than the one
  pinned on first contact; before, only claims and lookups stopped.
- Lightning Addresses whose invoices carry no or another description hash are paid, as LUD-06 now has the wallet
  check only the amount; before, addresses on other lnurlcash mints couldn't be paid.
- Notes in a payment, move or renewal whose answer never came back are out of the balance until the mint has
  settled it, and are never picked for another payment meanwhile; before, they still counted, though the mint might
  already have spent them.

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
