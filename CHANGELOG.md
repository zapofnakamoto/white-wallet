# Changelog

Notable changes to White Wallet, newest first ([Keep a Changelog](https://keepachangelog.com/)). A `v*` tag
publishes its section as the GitHub release notes, which brands that follow White Wallet's releases pick up.

## [Unreleased]

### Added

- Settings pages have an icon on their row: on the settings page, on the account's page and in search results, so a
  page is found at a glance. "Back up now", an unverified recovery phrase and "Delete" are marked in the error colour.
  A note that is still being paid, unclear or not yet certified shows a small icon before its status. Brands can
  replace the drawables, restyle the marks (`ListIcon`) and give their own Settings pages one (`BrandScreen.icon`).
- Accounts have a mark: the first letter of their name on a colour of their own, by their place among the accounts
  (oldest first), so neighbouring accounts differ, the same on every screen. In the account switcher, where the
  active account's mark has a check instead of a radio button, on the wallet tab's account chip, on Settings' account
  row, and on Move funds (from, and each account to move to). Brands restyle it (`AccountMark`).
- Brands can decorate a handed-out note's page: the `NoteShared` anchor, above its amount. Its status is the new
  `WalletStatus.WaitingForRecipient` while nobody has redeemed the note, `Sent` once someone did, and `Idle` once it
  was taken back. A brand's `when` over the status picks the new case up in its `else` branch.
- Brands choose what users can do with their notes (`noteActions`: `share`, `split`, `combine`, `move`, `recheck`,
  `renew`; all by default). Without an action that works on several notes, the notes view has no selecting.
- "Note" is a brand term, like "mint": the 80 strings that name the note (German "Schein") now sit in each feature
  module's block of brand terms, renamed *Brand terms*, and docs/WHITE_LABEL.md lists them all, with those a brand
  never shows under `notesView=hidden` or `historyFilter=none`. A brand that calls notes something else overrides
  these, per language, as whole sentences. No key changed. `dev/new-brand.sh` writes them into a new brand's
  commented-out strings, and a test fails on a new string outside the blocks that names the mint or the note.
- Brands design the note once: `NoteFace` draws it as a row of the notes list and large on its details, the claim
  preview and a handed-out note's page, around its QR code; `NoteGroupHeader` heads one mint's notes. They get the
  amount, a `NoteStatus`, the age, the mint (none with `showMints=false`) and whether it is trusted; the app keeps
  taps, selection, actions and the bearer warning. The defaults look as before, except that a note's details show
  its amount as large as the claim preview does. `brand-examples/acme/` shows it as digital cash: it says "banknote"
  ("Geldschein") and draws notes as banknotes.

### Fixed

- With `showMints=false`, the notes view no longer names mints: no mint headers, labels or totals (one mint's notes
  are a plain list, several mints' are separated by a line), no mint on a note or a shared note, no mint count, and an
  unanswered recheck says "No answer" without the mint.

## [0.4.0]

### Added

- Debug builds say which build is running: `git describe` (`v0.3.0`, or `v0.3.0-21-gb1593f9-dirty` after the tag)
  beside the release badge at setup and in About; on the wallet tab on a line below the title and badge. Also for a
  stable release. Shortened from the start only where it is wider than the screen. Release builds show nothing and
  stay reproducible. `LocalBuildLabel`, `WalletBuildLabel`.
- An empty wallet says how money gets in: a line under the ₿0 balance (not while the balance is hidden). Brands can
  reword it (`home_wallet_empty`).
- With `allowMintChange=true`, users can change their home mint from the mint pages (with `showMints=true`):
  "Change home mint" on the home mint's page, "Make this my home mint" on another one's, picking from the mints they
  already know (a new one is added under Mints first). Their name there becomes the primary address and invoices are
  paid there from then on. The old address keeps working and the old home stays trusted. Changing the home moves no
  money by itself. `Wallet.changeHome`.
- Moving money between mints, shown before it happens: a summary per mint of what arrives and the fee, or why it
  stays; the button names the amount. When the home mint changes, the money on other mints can go along: an account
  that collects at its home mint moves what its fee cap allows; one that doesn't chooses ("Change home mint" alone,
  or "Change and move", with a checkbox per mint). A mint's page also offers "Move…" for its money, to any known mint; while the account collects at its home
  mint, only to the home (and not from it).
  Afterwards a result says per mint what arrived or why it didn't move, with "Try again". `Wallet.previewMove`,
  `Wallet.moveMoney`.
- Mints for the device and for the account: Settings → Mints lists every mint this device has used, for all
  accounts, with its trust, its key and whose home it is, and nothing about one account's money; an unused mint can
  be removed there (its key and trust are forgotten, notes and history stay). Account → Your mints lists the active
  account's mints with its money there, and its pages have the account's actions, including "Move all money here"
  on the home. `Wallet.mints` (device-wide now), `Wallet.accountMints`, `Wallet.removeMint`.
- Five new brand-term strings for it: `mint_change_home`, `mint_make_home`, `settings_account_mints`,
  `mints_home_mint_of`, `mints_remove_mint`. Brands with their own word for the mint override them too.

### Changed

- The party that holds the funds is a mint everywhere. About 40 strings said "service" or "host" for it; they are
  written without the word where that reads naturally ("✓ Trusted", "The notes are already there."), and the
  recipient of a payment is "the recipient's wallet". Fifteen that need the word say "mint" and are brand terms now:
  `activity_moved_mint_other`, `notes_mints`, `notes_move_title`, `notes_move_target`,
  `notes_problem_different_mints`, `onboarding_add_elsewhere_title`, `onboarding_add_elsewhere_body`,
  `restore_error_mint`, `addresses_add_elsewhere`, `settings_strategy_single_hint`, `mint_title`, `about_body`,
  `notes_collect_search`, `mints_intro`, `mints_add_field`. Brands with their own word override 28 strings per
  language (docs/WHITE_LABEL.md lists them).
- String keys say what the text says: `onboarding_service` is `onboarding_mint`, `onboarding_service_custom_host`
  `onboarding_mint_address`, `onboarding_custody` `onboarding_mint_custody`, the mint page's `service_*` keys are
  `mint_*`, and every other key that said service says mint. Brands rename their overrides;
  `dev/check-brand.sh` now names an override whose string the app no longer has.
- Brand options: `defaultServiceHost` is `defaultMintHost`, `serviceSuggestions` `mintSuggestions`,
  `allowServiceChange` `allowMintChange`.
- The app says "Renew" wherever it said "Rotate" (Settings: "Auto-renew incoming notes"; the developer keys page);
  keys `keys_rotate*` are `keys_renew*`, `settings_auto_rotate*` `settings_auto_renew*`.
- In code, `Service` is `Mint`: `Wallet.connectMint`, `Wallet.mintDetails`, `Wallet.moveToMint`,
  `Wallet.quoteMintMove`, `ActivityKind.MovedMint`, and `MintNotes` for a brand's `NoteStrategy.plan`. Installed
  wallets keep their data: the database is migrated (version 3) and the developer address limit moves to its new key.

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
