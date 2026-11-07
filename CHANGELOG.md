# Changelog

Notable changes to White Wallet, newest first ([Keep a Changelog](https://keepachangelog.com/)). A `v*` tag
publishes its section as the GitHub release notes, which brands that follow White Wallet's releases pick up.

## [Unreleased]

### Added

- LUD-25/26 at luds `92e430f` (lnurl-mint 0.13), next to the draft `2f17578` (lnurl-mint 0.12): the wallet asks each
  mint which it speaks. New notes use BIP-32 keys, one account `S/a'` at a time. Notes and addresses from before move
  to the new keys by themselves on a refresh, for free; a mint upgraded later is noticed. A restore finds both.
- One share per Lightning address on a current mint, so addresses of an account can't be linked by their keys.
  Whether an address is yours is now proven to the mint, without changing anything there.
- Address fees: a mint may charge for an address. Setup shows the fee and lets the user reserve the name, pay later or
  go on without one. A reserved name is on the addresses screen until paid or expired.
- Payments to someone on the same mint go through the payee's pay callback, for free and with nothing pending.
- Wallets of several accounts: a recovery phrase can hold several accounts, each with its own money and addresses,
  and the app can hold several wallets. The switcher groups them; backup and delete act on the wallet, and delete
  names the accounts it takes with it. A restore finds a wallet's accounts on that mint, up to the first unused one.
  On a legacy mint only a wallet's first account can have an address. Database version 5.

- A wallet without a mint: setup and restore may go on without one where the brand lets users add a mint later
  (`mints.changeable=true`, `mints.visible=true`, an address not required). Home asks for a mint until there is one,
  and Receive points there; the first mint added becomes the home of every account, and finds what the phrase holds
  there. A restore is the phrase, then adding the mint picked.
- The seed-derived default address of a new account comes from its first share, so each account of a wallet has its
  own; those made before are still found by a restore. The watch-only key moved from the mint's page to each
  address: a key only ever shows what was paid to its own address.

### Changed

- Each wallet has its own mints: the signing key it pinned there and whether you trust a mint are the wallet's, and
  another wallet on the phone never sees or changes them. A mint's new key is accepted per wallet. An update gives
  every wallet the mints and trust choices the phone had. Database version 6.
- A home mint can be removed like any other mint, once nothing of the wallet is left there: its accounts have no home
  until the user picks one, which Receive asks for.
- A refresh, in the app or in the background, looks after the active wallet only. Another wallet's payments,
  received notes and open operations are taken care of once it is active.

### Fixed

- A mint's own name (`mint`) was offered as free on a LUD-26 mint.
- Adding a mint finds what every account of the wallet already holds there, as a restore does on its mint, and creates
  the accounts this phone didn't have yet. Payments to addresses found there keep arriving. A scan that fails says so,
  and adding the mint again goes on from there.
- A lookup on one mint no longer marks a note of another mint on the same domain as unknown or spent.
- A refused payment says why: an invoice of your own mint, one already used, or else the mint's own words. A bare invoice
  whose payment failed no longer offers "Try again", which a mint always refuses.
- The developer key screen has a tab per address, so address notes on new keys can be scanned and renewed there. Its
  Change tab could show the count of addresses instead of the change index.
- After a restore, new payments to an address were only found once its name was entered again. Every share the account
  handed out is now scanned on a refresh, on its home mint, whether or not the phone knows the name.
- A payment, a move or a combine of more than 100 notes was refused by the mint: all their notes went into one request.
  They now merge 10 notes per request.
- A restore stopped after 3 unused shares, so money on an address after three that were never paid was missed. It now
  stops after 20.

### For developers

- `wallet.properties` holds the numbers that decide how the wallet behaves, such as how far a restore or adding a mint
  looks (`scan.*`) and how many notes one merge request spends. A build can override one with `-P` or a `WW_` variable, a release build can't.
  Brands don't set these (see "Wallet settings" in `docs/DEVELOPMENT.md`).

### For brands

- New keys: `wallets.max`, `addresses.fee.onboarding` (`ask`, `reserve` or `skip`), `addresses.fee.max`.
- `accounts.max` now counts accounts per recovery phrase, not per app. A brand that wants one account in all sets
  `wallets.max=1` and `accounts.max=1`: `wallets.max` defaults to no limit, so with `accounts.max=1` alone users can
  still add wallets.
- `Wallet.onboard` and `createAccount` take `inActiveWallet`; `OnboardResult` has `AwaitingFee`, `FeeRefused` and
  `MintTooOld`, so an exhaustive `when` over it needs them. `deleteActiveAccount` deletes the whole wallet.
- `KeySet.LegacyWallet`, `LegacyChange`, `LegacyAddress` and `LEGACY` moved to `whitewallet.core.wallet.legacy`.
  `Note` and `OutputRef` default to `KeyScheme.Bip32`; `Requests.servicePayUrl` asks for `mint`, not `_`.
- New strings, for brands that word their own: `address_fee_*`, `onboarding_error_fee*`,
  `onboarding_error_mint_too_old`, `money_error_mint_too_old`, `accounts_wallet`, `accounts_add_wallet`,
  `delete_accounts`. `delete_what` and `accounts_separate_backups` say something new.

## [0.7.0]

### Added

- A note received as a link is kept with its spend from the moment it is claimed or kept, so a bad connection no
  longer loses it. Opened offline, the claim screen offers "Keep and claim later"; a refresh claims it once the mint
  can be reached, and the app runs one as soon as the device is online again. Until it is claimed, it is shown next
  to the balance, never in it: a line under the balance, a card on Home listing each such note and where it stands,
  and a section in the notes view, each saying what that means (not in the balance, not covered by the recovery
  phrase, still spendable by whoever made the link). One that someone else spent first can be removed, and so can
  one its mint kept refusing for a week, which the wallet then stops trying. Database version 4 (a new table;
  installed wallets keep their data).
- A note on a mint that now presents another signing key than the one the wallet pinned can be kept: nothing is
  sent there, Home shows the mint's key-change card, and the note is claimed once the user accepts the new key in
  the mint's details. It used to be refused as invalid.
- Script notes: a note whose link carries a script-path spend (`cw1`) can be claimed. The wallet checks the spend
  itself, without the mint, and reads any time it claims from the spend, never from the script. A note locked until
  a date, or for a while after its mint last credited it, says so on the claim screen and can be kept; the wallet
  claims it once the lock has passed. A note that may be spent another way (other script paths) is marked as such.
  A script of any length is read; one over 252 bytes used to crash the claim screen.
- A whole note can be handed out offline (Notes → Share as QR): its link comes from the recovery phrase, not the
  mint. The note screen says its mint hasn't confirmed it yet; the first refresh online confirms it, or finds it
  spent. A shared note is never taken back on its own, only when the user does it.
- A shared note's screen says when its mint last answered for it.
- New strings: `note_unconfirmed`, `note_unknown_to_mint`, `claim_not_spendable` and `claim_other_paths` (brand terms, which a brand that renames the note
  overrides), `claim_offline`, `claim_amount_certified`, `claim_amount_declared`, `claim_locked_until`,
  `claim_locked_for`, `claim_duration_minutes`, `claim_duration_hours`, `claim_duration_days`, `claim_keep`,
  `claim_kept`, `claim_kept_until`, `claim_kept_locked`, `claim_kept_key_changed`, `claim_kept_hint`,
  `claim_key_changed`, and in the design system
  `received_title`, `received_hint`, `received_total`, `received_unconfirmed`, `received_unknown_amount`,
  `received_on`, `received_waiting`, `received_locked_until`, `received_locked`, `received_key_changed`,
  `received_claiming`, `received_claimed`, `received_gone`, `received_refused`, `received_dismiss`. `claim_network`
  is gone.
- Brands can set the wallet's timings in `brand.properties` (see "Timings" in docs/WHITE_LABEL.md): mint timeouts
  (`network.timeout.connect`, `network.timeout.read`), background and on-screen refreshes (`sync.background`,
  `sync.receive`, `sync.invoice`, `sync.sharedNote`), how long a payment is waited for (`sync.paymentWait`), the app
  lock's grace period (`security.appLock.relockAfter`), when a copied secret is cleared
  (`security.clipboard.clearAfter`), how long collecting leaves a failed mint alone (`notes.collect.retryAfter`), how
  long a refused received note is tried (`notes.received.giveUpAfter`), and the fee caps users pick from
  (`notes.collect.feeCaps`). Durations carry their unit (`30s`, `15m`, `7d`); a value out of range fails the build.
  Every default is what the app did before.
- `security.appLock`: the app lock can start on (`on`) or be always on with no switch (`always`). A phone without a
  screen lock now opens the app even with the lock on, rather than locking its owner out.
- `backup.required=setup`: a new account's recovery phrase is written down and checked during setup, with no way
  past it except on a phone without a screen lock. `backup.reminder.above`: Home's backup reminder waits until the
  balance is above that many sats.
- The sentences that name these timings follow the brand's value, in whole sentences per unit: strings
  `security_app_lock_hint`, `backup_copy_body` and `backup_copied` are now `security_app_lock_hint_always` and the
  plurals `security_app_lock_hint_seconds`, `_minutes`, `_hours`, `backup_copy_body_seconds`, `_minutes`,
  `backup_copied_seconds`, `_minutes`; a brand that overrode them needs to rename them. `received_refused` no
  longer says "a week": the plurals `received_refused_hours` and `received_refused_days` say how long it was tried.
  New string: `security_app_lock_on_always`.
- Code: `CollectDefaults.FEE_CAPS` is now `CollectDefaults.feeCaps` (the brand's), and `SensitiveClipboard.copy`
  takes how long until the clipboard is cleared (`AppDefaults.timings.clipboardClearMillis`).

### Changed

- The note strategy starts on "Manual"; "Keep only one note" is a choice under Note settings. An account
  that never picked a strategy switches too, so its notes are no longer combined on their own. Brands:
  `notes.strategy.default` now defaults to `manual`.
- Home is quieter: "Scan a QR code" is an icon in the top bar instead of a button under Send and Receive,
  which stand alone as the wallet's two actions.
- One scanner does for both: a note scanned or pasted under Send opens Claim, and an invoice or Lightning address
  under Claim opens Send. Home has "Scan a QR code", so there's no choosing between paying and claiming first.
- Fewer words where nothing needs the user: a certified amount is no longer labelled as such (only one that can't
  be verified is), and the bearer warnings and the hint for a kept note are shorter. A shared note waits to be
  "claimed", not "redeemed".
- New strings: `note_checked_at`, `home_scan`, `scan_title`, `scan_hint`, `scan_unknown`.
- Strings `claim_certified`, `claim_amount_certified` and `notes_certified` are gone; `claim_kept_hint`,
  `note_bearer_warning`, `claim_bearer_warning`, `note_waiting`, `activity_note_waiting`, `notes_waiting_total`,
  `notes_shared_title`, `note_unconfirmed`, `note_unknown_to_mint`, `note_checked_at`, `note_reclaim_body` and
  `scan_hint` say less or say it differently.
- A shared note found spent says "Spent", not "Redeemed": usually the recipient took it, but another device with the
  same recovery phrase may have. Strings `note_redeemed`, `note_already_redeemed` and `activity_note_redeemed` are
  now `note_spent`, `note_already_spent` and `activity_note_spent`; a brand that overrode them needs to rename them.
- Your own shared note, scanned or pasted on Claim, is not claimed: the screen says you shared it and offers "Take
  back", which does what Take back on its note screen does. Offline it says it can't be taken back yet, and nothing
  is kept. Claimed in another account of the same wallet it is received there, and its own account finds it spent.
  New strings: `claim_own_shared` and `claim_own_offline` (brand terms), `claim_take_back`, `claim_taken_back`,
  `claim_take_back_failed`.
- The auto-renew setting says what it renews: notes paid to your Lightning address. Notes on wallet and change keys
  were never renewed and still aren't; tests now prove it. Strings `settings_auto_renew` and
  `settings_auto_renew_hint` say it differently; a brand that overrode them should update its wording.
- Settings has no search any more: the hub's few pages are quicker to scan, and every setting needed keywords in
  every language that brands would have had to override to match their own words. Strings `settings_search`,
  `settings_search_clear`, `settings_search_empty`, `notes_collect_search` and all `search_kw_*` are gone (a brand
  can drop its overrides). Code: `SearchDestination` is now `SettingsDestination`, and `SettingsRoute` and
  `SettingsScreen` no longer take `developerPages`.
- Code is formatted by ktfmt (kotlinlang style, 140 wide) through Spotless: `just format` formats, and `just check`
  and CI fail on code that isn't formatted. A brand's code in `brand/code` is formatted with the rest.
- Brand API: `WalletEvent.NoteRedeemed` is now `WalletEvent.NoteSpent`, and `NoteStatus.Redeemed` is now
  `NoteStatus.Spent`; a brand's code that uses them needs the new names.

### Fixed

- A claim that never reached the mint is tried again, instead of ending as failed with only the link to retry it.
- A claim whose note someone else took first, while its answer was lost, is settled as gone. It used to stay
  unknown, and was looked up again on every refresh.
- A link the wallet already claimed says it is in the wallet, not that it was spent.
- Looking at a note link no longer sends its spend to the mint. The spend goes out only with the claim, after the
  mint's signing key is compared with the pinned one: a mint presenting another key gets nothing.
- Online, a note is certified by the mint's own certificate, whatever certificate the link carries. A link with
  another note's certificate used to show the note as not certified.
- A certificate whose amount is too large to read is no certificate. It used to crash the claim screen.
- Combining, splitting, renewing, paying and moving no longer send a note's spend to look the note up first: every
  note is checked by its key, and a spend goes out only with the operation that spends it, to the mint's own host,
  after its signing key is compared with the pinned one.
- The claim screen blocks screenshots and the Recents thumbnail, like the other screens that show a note's link.
- `claim_key_changed`, `claim_kept_key_changed`, `note_unconfirmed`, `note_unknown_to_mint` and `scan_unknown` are
  brand terms, as they name the mint or the note: a brand that uses other words overrides them too. They were outside
  the brand-term block, so a brand couldn't know to.

## [0.6.3]

### Fixed

- Splitting, renewing, combining, claiming, receiving by invoice and moving money to another mint go on past every
  key the mint holds for unpaid invoices, up to 20 in a row, so they work the first time. They used to give up after
  three; another phone with the same recovery phrase leaves such keys behind, and the mint never frees them. The
  wallet looks its keys up again once per operation, not after every refused key.

## [0.6.2]

### Fixed

- Splitting, renewing or combining notes no longer fails for good with "already in use". A mint holds the key of
  every note an unpaid invoice would make (from Receive, a move that didn't go through, or another phone with the
  same recovery phrase), but a lookup calls such a key unknown. Finding its keys again, the wallet then went back to
  keys it had already used, and tried the same refused ones until it gave up. It now never goes back, so the next
  attempt takes free keys.
- A restore no longer ends with a balance of nothing when the mint refuses to look a key up: it stops and says the
  mint can't be used, so nothing is missed. A note being paid out while the restore runs counts as used, so the notes
  after it are found. Refreshes that look for Lightning address payments stop the same way.

## [0.6.1]

### Fixed

- A debug build of a brand in a repository of its own shows that repository's version beside the title, not White
  Wallet's. Release builds show none, as before.

## [0.6.0]

### Added

- A note the mint says it doesn't know (a mint that lost its records, or misbehaves) no longer stops the wallet. It
  used to stay in the balance, so "keep only one note" stopped combining, and payments planned from it failed. Now,
  when a mint refuses such a note, the wallet looks each note up and sets the unknown ones aside: out of the balance,
  never spent, kept with their certificates. The other notes are combined and paid from as usual. Refreshes ask
  the mint again (each refresh for a day, then once a day), and a note the mint knows again is back in the balance.
  Home shows a warning per mint with "Check again", the notes view marks the note "Unknown to the mint", and a
  recheck reports such notes separately from spent ones. New `NoteState.Unknown` and `NoteStatus.Unknown` (a brand's
  note face may show it faded). New strings: `notes_state_unknown`, `notes_state_unknown_detail`,
  `notes_checked_unknown`, `home_unknown_title`, `home_unknown_title_unnamed`, `home_unknown_body`,
  `home_unknown_body_hidden`, `home_unknown_action`. All but `home_unknown_action` are brand terms, which a brand
  that renames the mint or the note overrides.

- "What's new": release notes show once in a sheet on Home after the update that brings them, and stay listed under
  About → What's new. Important notes (something to do, a coming change) show on a fresh install too, and come back at
  every start until the user taps "Got it". White Wallet
  has none yet. A brand binds its own list in its code module (`whitewallet.brand.ReleaseNotes`, see
  docs/WHITE_LABEL.md, "Release notes"), which takes the place of White Wallet's. The sheet uses the brand's
  `Sheet` and `Card`. New strings: `release_notes_title`, `release_notes_done`.

## [0.5.0]

### Added

- Brands can let users bring their own mint: an empty `mints.suggestions` offers no mint at setup, only the field to
  type one. The new `mints.custom` (default `true`) decides whether users may use mints the brand doesn't suggest;
  with `false`, only the suggested mints can be a home, hold an address or receive moved money (setup offers only
  them, Settings → Mints adds one that's missing from them), while notes from any mint can still be claimed. One
  suggestion without `mints.custom` is the brand's mint and nothing else. The mint picker at setup shows only when
  there is a choice, and `mints.changeable` now only concerns what users may do after setup.
- Developers can try the app on a bad connection: in debug builds with developer mode on, Developer options →
  Network makes every request to a mint slow (0.2 s), very slow (6 s) or flaky (up to 4 s, and one in five never
  reaches the mint). It stays set across restarts, so a cold start can be tried too, and the request log shows the
  delays and drops.
- Home no longer shows a balance of nothing, or figures about to change, while the wallet works: a loading screen
  takes the place of the balance, notes and history once the work takes more than a moment, and says what the
  wallet does: updating the balance, checking note 3 of 5, or the note maintenance step (combining, splitting,
  renewing, moving money to the home mint). Quick work, and a refresh with nothing to ask a mint, leave the figures
  on screen. Brands draw the loading screen at the new `Loading` anchor, with the new status
  `WalletStatus.Working` and its `WalletTask`, and a note strategy can name its own steps (`NoteStrategy.workingText`).
- Developers can make a note action fail on purpose to see its failure screens: in debug builds with developer mode
  on, a note's sheet offers, marked as for developers, to make the next change fail (no answer, refused, answer
  lost), used up by the next share, split, renew, combine, move or recheck. The wallet takes its real path, a lost answer included: the note stays open until a
  refresh settles it.
- The wallet says what it did to the notes by itself: a short message on the home screen when its note strategy
  combined or split them, auto-renew renewed them or collecting moved them home, with what that did to the balance
  ("Combined 2 notes into one of ₿63. You got ₿1 in fees back."), and Details to see it in History, even where the
  brand offers no History filters. It shows while the home screen is up, and after a claim or a payment only if the
  balance changed. Users turn it off under Note settings. Brands decide with `notes.maintenance.notices`
  (`hidden`, `off`, `on` by default, `always`) and `notes.maintenance.notices.whenNotesHidden`, and can restyle the
  message (`Snackbar` in `BrandComponents`).
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
- Brands choose what users can do with their notes (`notes.actions`: `share`, `split`, `combine`, `move`, `recheck`,
  `renew`; all by default). Without an action that works on several notes, the notes view has no selecting.
- "Note" is a brand term, like "mint": the 80 strings that name the note (German "Schein") now sit in each feature
  module's block of brand terms, renamed *Brand terms*, and docs/WHITE_LABEL.md lists them all, with those a brand
  never shows under `notes.view=hidden` or `history.filter=none`. A brand that calls notes something else overrides
  these, per language, as whole sentences. No key changed. `dev/new-brand.sh` writes them into a new brand's
  commented-out strings, and a test fails on a new string outside the blocks that names the mint or the note.
- Brands design the note once: `NoteFace` draws it as a row of the notes list and large on its details, the claim
  preview and a handed-out note's page, around its QR code; `NoteGroupHeader` heads one mint's notes. They get the
  amount, a `NoteStatus`, the age, the mint (none with `mints.visible=false`) and whether it is trusted; the app keeps
  taps, selection, actions and the bearer warning. The defaults look as before, except that a note's details show
  its amount as large as the claim preview does. `brand-examples/acme/` shows it as digital cash: it says "banknote"
  ("Geldschein") and draws notes as banknotes.
- A handed-out note can be printed: "Print" on its page draws the brand's `NoteFace` with the QR code and a warning
  that whoever holds the paper can spend it, on white in the brand's light theme whatever the phone's, and opens
  Android's print dialog. Brands turn it off by leaving `print` out of `notes.actions`. Designsystem: `PrintPage`,
  `printBitmap`, `LightWalletTheme`.
- Brands can decorate the notes view: `NotesHeader` above its total (status `Empty`, `WaitingForRecipient` while
  handed-out notes wait, or `Idle`) and `NotesEmpty` above the text of an empty one. Three new `WalletEvent`s:
  `NoteRedeemed` and `NoteTakenBack` when a handed-out note is redeemed or taken back, and `NoteMaintenance` when a
  note strategy, auto-renew or collecting at the home mint looked after the notes by itself, once per run, with what
  it did (notes combined, split and renewed, the amount moved home, notes spent and made, the change to the balance).
  A brand's `when` over events picks them up in its `else`.
- A handed-out note's QR code is covered until the user taps "Tap to show the QR code", so nobody nearby can scan it
  by chance, and covered again whenever the app leaves the screen. Share, Copy and Print work as before; the printed
  page has the code. Brands see the cover in `NoteFace`'s `qrCode` slot (`qrCovered`) and can draw their own
  (`QrCodeCover`).
- A brand's `NoteFace` knows where the note is (`place`: in the wallet, on the claim preview, on a handed-out note's
  page, on paper), so a note can say "ready to claim" only to the one about to claim it, or print in less ink.
- Handed-out notes in the notes view are the brand's `NoteFace` rows too (status `Waiting`, place `Shared`); before,
  they were plain list rows a brand couldn't style. The default row looks as before, with the mint below the amount.

### Changed

- Brands: `mints.default` is gone; the first of `mints.suggestions` is preselected instead, so a brand moves its
  default there (a brand still setting `mints.default` fails the build with that hint). `mints.changeable=false` no
  longer hides the mint picker at setup: one suggestion with `mints.custom=false` does.
- A note stuck at "Confirming…" (a change whose answer never came) can be rechecked: the wallet asks the mint
  right away whether the change went through, instead of waiting for the next refresh, and says how it ended.
- Splitting a note starts at half of what is left after the fee. A note too small for two notes of whole sats
  (2 sats with a 1-sat fee) says so right away instead of opening a split that can't be done.
- Acme's example strategy is now "Combine small banknotes" (`combine-small`, `CombineSmallNotes`).
- History no longer says note maintenance doesn't change the balance: a merge's refund or a split's fee does.
- brand.properties keys are grouped by what they concern: `app.*`, `mints.*`, `accounts.*`, `addresses.*`,
  `amounts.*`, `notes.*`, `history.*`, `developer.*`. A brand still using an old key fails the build with its new
  name. `appName` → `app.name`, `applicationId` → `app.id`, `versionCode` / `versionName` → `app.version.code` /
  `app.version.name`, `releaseStage` → `app.releaseStage`, `appearance` → `app.appearance`, `supportUrl` →
  `app.supportUrl`; `defaultMintHost` → `mints.default`, `mintSuggestions` → `mints.suggestions`, `allowMintChange` →
  `mints.changeable`, `showMints` → `mints.visible`, `trustedMints` → `mints.trusted`; `maxAccounts` → `accounts.max`,
  `maxAddresses` → `addresses.max`, `lightningAddress` → `addresses.lightning`; `defaultAmountUnit` /
  `allowUnitChange` → `amounts.unit.default` / `amounts.unit.changeable`; `notesView` → `notes.view`, `noteActions` →
  `notes.actions`, `noteStrategies` → `notes.strategies`, `defaultNoteStrategy` → `notes.strategy.default`,
  `collectAtHomeMint` / `collectFeeCap` → `notes.collect.atHomeMint` / `notes.collect.feeCap`; `historyFilter` →
  `history.filter`; `developerOptions` → `developer.options`.

### Fixed

- Setup can't go on without a mint: with an empty mint field, "Get started" stays off and the name isn't checked
  against no mint.
- A note's details and the account switcher scroll: on a small phone, or with many accounts, their lower part
  (hints, buttons) was cut off.
- A note's details open at full height, and an action's outcome scrolls into view: a failed split or recheck said
  so below the half-open sheet's edge, out of sight. Brands' `SheetParams` gain `openFully` for this.
- The notes view's sort ("Largest first", "Newest first") orders the handed-out notes too; they were always newest
  first.
- A handed-out note taken back is combined with the others straight away under "Keep only one note" (or the brand's
  strategy), as when it is claimed back through its link; it stayed a note of its own until the next refresh, and the
  base fee the merge pays back only showed then.
- With `mints.visible=false`, the notes view no longer names mints: no mint headers, labels or totals (one mint's notes
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
