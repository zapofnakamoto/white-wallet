# Changelog

Notable changes to White Wallet, newest first ([Keep a Changelog](https://keepachangelog.com/)). A `v*` tag
publishes its section as the GitHub release notes, which brands that follow White Wallet's releases pick up.

## [Unreleased]

### Added

- A note waiting for its recipient has a switch below it: `lnurlw://` or `LNURL`, for wallets that read only bech32
  LNURL1 links. The QR code, Share, Copy and Print follow it; every note starts on `lnurlw://`. A `lnurlw://` or
  `lnurlp://` link wrapped in bech32 by other wallets (against the spec, and never made here) is read too. For brands:
  the strings `note_format_lnurlw` and `note_format_lnurl` name the two options.
- Claiming a note (or a link received over Lightning), taking back a note from its link, moving money to another
  account, to another mint or home, a new home mint, using a mint's new signing key while adding it, and a finished
  backup now end on the success screen too, as sending and receiving do. Only what worked all the way does: a note
  kept for later, money still on its way or left behind keeps its own result.
- Using a mint's new signing key on its page says so in a short message ("mint.example now uses the new key."), as do
  a new primary Lightning address and one that was unregistered; what went wrong there stays on the page.
- For brands: the anchors `MintKeyAccepted`, `NoteClaimed`, `NoteTakenBack`, `MoneyMoved`, `HomeChanged` and
  `BackupDone`, each a check mark that draws itself by default (docs/WHITE_LABEL.md); `ClaimResult` is now only above
  a claim's or a take-back's result that isn't a success. The brand term `money_home_changed_title` ("Home mint
  changed").

- Adding a mint (outside a wallet's setup), a Lightning address or an account, and a scan for notes that got through,
  now end on a success screen like sending and receiving do: the brand's picture, a heading, what was found (the same
  words on every screen) and one button on, kept at the bottom of the screen. A new address shows itself with Copy
  and Share, and that it receives even while the phone is off (`claimed_body`, new words); one the app couldn't check
  still says so on its own screen. A new account shows what it already
  held, and opens from there.
- For brands: the status `WalletStatus.Done` (with what was found) and the anchors `MintAdded`, `AddressAdded`,
  `AccountCreated` and `ScanDone`, each a check mark that draws itself by default (docs/WHITE_LABEL.md). The brand term
  `mint_added_title` ("Mint added").
- For developers: `SuccessScreen` takes its buttons as `SuccessAction`, an optional amount, `lines` and extra
  `content`, and keeps its buttons at the bottom; back does what its main button does. `CopyShare` is in the design
  system. docs/DEVELOPMENT.md, "Success screens", says how to add one.
- A wallet's setup ends on a success screen too, "Your wallet is ready": the first wallet's and an added one's, created
  or restored, whether or not it got a mint or an address. It shows the address the setup added (with Copy and Share,
  in place of the address's own screen), what a restore found, and, when the phone holds other wallets, that this one
  is in use now. Before, a setup without an address opened the main screen without a word.
- Settings → Wallets marks the wallet in use with the check the account in use carries.
- For brands: the anchor `WalletReady`; `ListIconParams.activeBadge` (the wallet in use; a `ListIcon` of their own
  should draw it); `BrandScreenEntry.AfterEverySetup`, a page after every wallet's setup.
- For developers: Developer options → Network → **Offline** (debug builds, developer mode on): no request goes out and
  every screen behaves as with the phone offline, while other apps keep the network.
- For brands and developers: screenshots. `just screenshots [brand dir]` draws a preview of every shared screen in its
  everyday state, and the success screens and the empty state in each of theirs, in a brand's design, light and dark, with a large font and in German, and
  shows what changed since the last run; `just check` and CI draw a small set for each sample brand and compare it
  with the push before (docs/DEVELOPMENT.md, "Screenshots"; docs/WHITE_LABEL.md §3). Nothing is committed.

### Changed

- A refused claim or move, and a mint that can't become the home, no longer show the app's own name for why ("Refused",
  "RateLimited", in English in every language): the mint's own words if it gave any, else a plain sentence.
- Adding a mint, setting up a wallet and the addresses page no longer show the app's or the phone's own English for what
  went wrong (a network error's text, "not a valid host", "the address is still registered"): each says it in the
  user's language. A mint that refuses keeps its own words, also where it is picked: before, a mint that answered with
  an error was called "not a mint".
- For brands: `add_mint_error_invalid`, `add_mint_failed_body`, `onboarding_error_network`, `addresses_status_failed`
  and `addresses_unregister_unknown` no longer take the error's text; new strings `onboarding_error_mint_plain`,
  `add_mint_error_not_a_host`, `addresses_status_invalid` and `addresses_unregister_failed_plain` say it without the
  mint's words.
- For developers: `OnboardResult.MintInvalid`, `RestoreResult.MintInvalid`, `MintPreview.Invalid` and
  `AddMintError.Invalid` say why as a `MintRefusal`, with the mint's own words apart (`mintReason`);
  `UnregisterResult.Failed` has the mint's words only, `UnregisterResult.NetworkError` none. The protocol throws
  `ServiceRefused` (a `ProtocolViolation`) with the service's reason where it answered ERROR instead of a request.
- Choosing a Lightning address no longer shows the app's English rule for a name ("use 1-32 lowercase letters, …"), in
  every language, nor its own English for a registration that failed ("no free share"): the rule is in the user's
  language, and a mint that refused the name keeps its own words. For brands: `onboarding_error_username` no longer
  takes the rule; new strings `onboarding_username_rule` and `onboarding_error_registration_plain`. For developers:
  `AddressStatus.Invalid` says why as an `AddressProblem`; `OnboardResult.UsernameInvalid` is an object, a mint
  that isn't a host is `MintInvalid(MintRefusal.NotAHost)`; `OnboardResult.RegistrationFailed` has the mint's words
  only (`mintReason`).
- For brands: `claim_done`, `claim_received` and `claim_taken_back` ("Claimed ₿…") became the success screens' titles
  `claim_success_title`, `claim_received_title` and `claim_taken_back_title` (no amount: the screen shows it);
  `add_mint_key_accepted` is now `mint_key_accepted` in the design system, with `mint_key_accepted_title`; new words for
  `money_error_mint` ("%1$s doesn't answer as expected.", the mint's address).
- A mint that shows no signing key is refused where it is shown, before anything is added: its page says that it can't
  be added and why, that the key may be back in a moment, and offers "Check again" in place of "Add this mint". A mint
  that couldn't be asked for its key says it "isn't answering" instead, and a mint the wallet holds isn't taken for one
  whose key changed when it only couldn't be asked. A mint that wasn't added no longer ends under "Error while
  restoring". For brands: `onboarding_error_mint_without_key` has new words (it no longer says the mint "publishes no
  signing key", which the wallet can't know), and `add_mint_key_unanswered` and `add_mint_check_again` are new. For
  developers: `MintPreview.Ready.keyAnswered`.
- A mint picked from the list of mints is asked at once what it is, so one that isn't answering or shows no signing key
  says so right there, before "Next", and keeps a line under its name in the list. "Next" uses that answer instead of
  asking again. No mint is asked before the user acts on it: not the one preselected, nor the others in the list, and
  a typed address only on "Next". For brands: `add_mint_pick_without_key`, `add_mint_row_not_answering` and
  `add_mint_row_without_key` are new.
- A button at work no longer turns grey with a spinner in place of its label. It is locked at once and keeps its label
  and its look; a small spinner appears beside the label only if the work takes more than a moment, and then stays its
  shortest time, as every other sign of loading does (`loading.indicator.*`). A quick check no longer makes its button
  blink. For brands that draw their own button (`BrandComponents.Button`): `ButtonParams.busy` now means "show
  progress beside the label", only once it is due, and `onClick` already does nothing while the button is at work;
  a button drawn the old way keeps working. For developers: pass `busy` as it is, not `showsWorking(busy)`.
- The texts about a mint whose signing key doesn't match no longer end with "Your notes there are unchanged": they
  already say that nothing is sent there, and the line also showed where the wallet holds no notes. On the mint's page,
  "no invoice is created there" lost its second "there". For brands: `home_key_changed_body`, `mint_key_changed`,
  `send_problem_key_changed` and `notes_problem_key_changed` have new words.
- Receive finds a payment to the Lightning address it shows sooner: it looks there at once, then every 5 seconds, and
  only at that address (its share, at its own mint) instead of refreshing the whole wallet every 15 seconds. For brands:
  `sync.receive` now defaults to `5s`. For developers: `Wallet.lookForPayments(address)`.
- A note received as a link and not claimed yet is calm while it only waits: Home says it in one line under the balance,
  which leads to the notes view, and its card holds only notes that need the user (a changed key, Lightning only, spent
  by someone else; every one where the brand has no Notes tab). Its row says what it waits for: "Claimed when you're back
  online.", "Claimed when mint.example answers." or "Claimed on the next refresh.", and offers Archive only where waiting
  won't help (a changed key, Lightning only, a lock that has passed). That it isn't safe until claimed is said once, where
  the user keeps it (Claim) and at the head of the notes view's section, in one wording. The notes view's status is
  offline like Home's.
- For brands: the brand term `received_waiting_mint_unnamed` ("Claimed when its mint answers."); new words for
  `received_waiting`, `received_hint` and `claim_kept_hint`.
- For brands: a page with `BrandScreenEntry.AfterOnboarding` shows after the setup of the phone's first wallet only,
  no longer after every added wallet's (`AfterEverySetup` does that). `AddressAdded` is no longer asked for in a
  setup: `WalletReady` is.
- For developers: the string `wallet_numbered` and `walletName()` are in the design system; `OnboardingRoute.onDone`
  gives the setup's address, and `AddMintFinish` no longer says whether the wallet is backed up (`WalletReadyState`
  does).
- In a wallet's setup, the page after its mint was added is titled "Mint added" and goes on with "Next", no longer
  "Done" and "OK": more steps follow. For brands: new words for `add_mint_added_body` ("Your new wallet uses it from
  now on."), which no longer repeats the title.
- The success screens of sending and receiving keep their buttons at the bottom of the screen, also with large text.
- With large text, a list row no longer splits its words: what stands on its right (an amount, a date, an action such
  as "Change home mint") goes below the text when beside it the text wouldn't fit, and a mint's name wraps after a dot
  ("mint." / "example") rather than inside it. A page title that wouldn't fit beside the back button and the page's
  actions ("Request log" beside Copy and Clear) gets a line of its own under them. For brands: a `ListRow` or
  `TopBar` of their own should do the same (docs/WHITE_LABEL.md, "Components").

### Fixed

- Invoice QR codes start with `LIGHTNING:` in capitals: with a lowercase prefix the whole code was encoded in the
  bulkier byte mode, so it came out denser than needed.
- A note handed out while offline that its mint doesn't know no longer gets stuck. Before, it stayed a waiting note
  forever, "Take back" said "Couldn't connect. Nothing was paid. Try again later." every time, and it couldn't be
  archived. Now, once online, it is set aside like any other note its mint doesn't know: the notes view shows it and
  it can be archived. "Take back" says that it is taken back as soon as the mint knows it again, and it then is. If
  the user didn't ask for it back, it waits to be claimed again once the mint knows it, and never comes back into the
  balance by itself, since its link is still out there. Home's warning about unknown notes counts only notes from the
  balance. Removed for good from the archive, it stays in History as "Claim not verified", never as spent. For brands:
  new strings `note_take_back_when_known`, `notes_state_unknown_handed_out_detail`, `notes_checked_handed_out`,
  `note_unverified` and `activity_note_unverified`, and new words for `note_unknown_to_mint`. For developers: database
  version 12 (`Note.handedOut`, `HandedOut`), `ReclaimResult.WhenKnown`, `NoteCheck.HandedOutAgain`,
  `NotesCheck.handedOutAgain`, `NotesOutcome.Checked.handedOut`, `SharedNote.takeBackAsked`,
  `ActivityStatus.Unverified` and `SharedNoteState.Unverified`. `SharedNote.unknownToMint` comes from the note's state
  now, and `SharedNote.confirmed` is false while its mint doesn't know it.
- The archive no longer tells a handed-out note that it comes back into your balance once its mint knows it again.
  Handed-out notes have a part of their own there, "Handed out", which says they wait to be claimed then, or come
  back only if you asked to take them back; a note's details say the same before you archive it. For brands: new
  strings `archive_handed_out_title`, `archive_handed_out_hint`, `notes_archive_hint_handed_out` and
  `notes_archive_hint_take_back`.
- In the notes view and the archive, a handed-out note its mint doesn't know says "Handed out · unknown to the mint",
  not just "Unknown to the mint" like a note from the balance. A note its mint doesn't know offers only Recheck and
  Archive in its details: Share as QR, Split and Renew, which couldn't be used on it, are gone. For brands: new string
  `notes_state_unknown_handed_out`.
- Taking back a note you handed out no longer says "Couldn't connect" when its mint answered: a refusal says so, with
  the mint's own words if it gave any, and a changed signing key says that. "Check again" in the notes view and the
  archive tells what happened to handed-out notes apart from the balance: spent, still unknown to their mint, known
  again and waiting to be claimed, taken back as you asked, or a take back the mint refused (your wallet tries again
  by itself). Home's "Check again" counts a take back among the notes back in your balance. For brands: new strings
  `mint_message`, `notes_checked_handed_out_spent`, `notes_checked_handed_out_unknown`, `notes_checked_taken_back` and
  `notes_checked_take_back_refused`. For developers: `ReclaimResult.Failed.mintMessage`, `NoteCheck.TakenBack` and
  `NoteCheck.TakeBackRefused`; `NotesCheck.handedOutUnknown`, `takenBack` and `takeBackRefused`, which `checked`,
  `gone` and `unknown` no longer count; the same counts on `NotesOutcome.Checked`.

### Removed

- For developers and brands: the strings `receive_copy`, `receive_share` (now `copy`, `share` in the design system),
  `claimed_copy` and `scan_done`.
- For developers: `Onboarding.MINT_WITHOUT_SIGNING_KEY`, `Wallet.ALREADY_HOME` and `OnboardingError.MintWithoutSigningKey`
  (a `MintRefusal` says it), and `Onboarding.USERNAME_RULE` (an `AddressProblem` says it).

## [0.10.0]

### Removed

- The older draft of LUD-25/26, `2f17578` (lnurl-mint 0.12), and with it the key derivation of before 0.8.0. A mint
  still on lnurl-mint 0.12 fails like any mint that refuses the wallet's requests. Since 0.8.0 the wallet moved notes
  and addresses from the old keys to the new ones by itself; this version no longer finds or moves them. Anyone who
  still holds some (a wallet not opened since before 0.8.0, a recovery phrase written down then) opens it once in
  0.9.0, which still moves them, then uses "Scan for notes" here. The database update (version 9) removes what is left
  on the old keys from the phone; the money stays at the mint. Updated users see this once under "What's new". Brands
  that need the old draft stay on 0.9.0.
- For developers and brands: the strings `add_mint_legacy`, `onboarding_error_mint_too_old` and `keys_legacy_address`,
  `keys_legacy_wallet`, `keys_legacy_change`; `OnboardingError.MintTooOld`, `MintDialect` and
  `LnurlcashClient.dialect`, `KeyScheme`, `MintPreview.Ready.legacy`. White Wallet's own release note takes two new
  strings, `release_note_old_keys_title` and `release_note_old_keys_body`, which a brand without its own
  `ReleaseNotes` shows too: override them with its own version numbers. The regtest stack is one stack again
  (`whitewallet-regtest`, lnurl-mint 0.13.1, three mints): `REGTEST_PROTOCOL`, the fourth mint with `upgrade-mint4`
  and `reset-mint4`, `just test-regtest-current` and `just test-regtest-legacy` and `dev/check-legacy.sh` are gone.
  The name was the legacy stack's: empty both old stacks once, with `just regtest reset` and
  `docker compose -p whitewallet-regtest-current -f dev/regtest/docker-compose.yml down -v`.

### Added

- Sending and receiving on a mint of your choice. With money on more than one mint, tapping a mint's row under the
  balance opens it in place: Send, Receive and Details come in over its name, the amount stays where it is. A mint's
  page has Send and Receive too. Receive says which mint it receives on ("On mint.example.com, your home mint ·
  Change"), and Send which mint pays ("From mint.example.com · Change"), once there is another to pick; a mint without
  enough for the payment can't be picked. The choice holds for that one payment: left alone, the wallet still receives
  on the home mint and pays as set in Settings. Creating a note works the same way. Nothing of it shows where the
  brand hides mints.
- For developers and brands: `Wallet.previewPayment`, `quoteNote`, `createNote` and `sendableNoteMsat` take the mint
  to pay from, `requestInvoice` the mint to receive on, and `Wallet.sendableByMint` says what each mint can pay;
  `PayQuote.Ok` names the mint that pays (`host`). The shared `MintChoiceLine` and `MintChoiceSheet` (designsystem),
  and new strings: `mint_choice_change`, `receive_on*`, `send_from*`, `home_mint_send`, `home_mint_receive`,
  `home_mint_details`, `home_mint_open`, `home_mint_opened`, `mint_send`, `mint_receive`. New brand terms (see
  docs/WHITE_LABEL.md): `receive_on_home`, `receive_on_home_detail`, `send_from_detail_home`; the guide now also lists
  `claim_mint_refused` and `claim_mint_refused_reason`.
- Notes a mint doesn't know can be archived, so Home's warning about them ends. Before, it stayed for as long as the
  mint didn't know them, which for a mint that lost its records is forever. "Archive" is on such a note's details,
  and on a selection of such notes in Notes (they are selected apart from the others: archived or rechecked only).
  An archived note stays out of the balance and is still asked about on refresh, once a day; one the mint knows
  again is back in the balance, out of the archive. Notes → Archive lists them under the received links, each to be
  rechecked now or removed for good. Removing one is safe: its key comes from the recovery phrase, so "Scan for
  notes" finds it should the mint ever know it again; a mint whose notes are all removed can be removed then. After
  a day, Home's card says they can be archived. The database update (version 10) adds when a note was archived.
- For developers and brands: `Wallet.archiveUnknownNotes`, `Wallet.removeArchivedNote`, `Note.archivedAtMillis` and
  `Note.isArchived`, `AccountMint.unknownGraceEndsAtMillis`, `WalletStore.deleteNote`. New strings
  `notes_archived_links` (instead of `notes_archived` and `notes_archived_hint`), `notes_archive`,
  `notes_archive_hint`, `archive_links_title`, `archive_note_back`, `archive_note_still`, `archive_note_no_answer`.
  New brand terms (see docs/WHITE_LABEL.md): `notes_archived_notes`, `notes_archived_done`, `archive_notes_title`,
  `archive_notes_hint`, `archive_note_remove_title`, `archive_note_remove_body`, `home_unknown_help_late`.
- A screen with nothing to show says why and offers the way forward, the same on every screen: History and Notes
  lead to Receive; Settings → Mints and Account → Mints to adding a mint; Lightning addresses to getting the first
  one, or without a home mint to adding or choosing one, as Receive does. "Change home mint" and "Move money" with no
  other mint offer adding one (where the brand lets users add mints) and come back with it picked.
- For brands: `BrandDesign.Body(page, context, content)` wraps a page's body, below its top bar, for empty and
  non-empty states alike (`context.status`, `Empty` while the page has nothing to show): a background, a frame, a
  picture by state. Call `content()` exactly once. Pages so far (`Page`): `History`, `Notes`, `Archive`, `Receive`,
  `Mints`, `AccountMints`, `Addresses`, `MoneyMove`; the default draws the body as before.
- A mint that doesn't answer is told, before the user finds out by paying. Home shows a card naming it and what that
  stops (for the home mint: receiving and spending there; for another mint: spending the money there), with "Check
  again"; its row under the balance says "Not answering". Several mints other than the home mint share one card ("3
  mints aren't answering"), as one "Check again" asks them all. Receive says so under a Lightning address whose mint is
  silent, and when the home mint can't make an invoice. A refresh asks the mints the account holds money on that it
  had nothing else to ask, so it knows. When the phone is offline, Home, Receive and Send say that instead, and blame
  no mint.
- A payment the first mint doesn't answer for, before anything was sent, is paid from the next mint that can; a mint
  that didn't answer the last time is tried last. With none, Send names the mint, and an invoice can be tried again,
  as none was paid.
- For brands: `ProblemKind.MintNotAnswering`, a mint the money is on (or that receives) doesn't answer while the phone
  is online, on Home, Receive and Send; `Offline` now only means the phone has no connection. New brand terms
  `home_mint_is_home_not_answering`, `home_mint_silent_title_home`, `home_mint_silent_title_unnamed`,
  `home_mints_silent_title`,
  `receive_address_mint_silent_unnamed`, `receive_invoice_mint_silent_unnamed` and
  `send_problem_mint_silent_unnamed`. The app now declares `ACCESS_NETWORK_STATE` (WorkManager declared it already).
  For developers: `Wallet.mintsNotAnswering`, `AccountMint.answering`,
  `PayProblem.MintNotAnswering` with `PayResult.Failed.host`.
- Being offline is told as it happens, and before the user tries what needs a connection. Home says so at once, and
  updates by itself once the phone is back, so it offers nothing to try while offline. Send, sending as a note,
  Receive's invoice, adding a mint and moving money say "You're offline" above the button that needs a connection,
  which waits for one; a note link entered on Send still goes to Claim, which keeps it. Claim, a shared note's "Take
  back" and a mint's page say the phone is offline instead of blaming the mint, and ask again once it's back. Back
  on a network, the app counts as offline until the system has found that the network reaches the internet (at most
  10 seconds, `back.online.verified.wait.millis` in wallet.properties): the first requests don't go out before the
  network carries them, and "offline" never gives way to a mint that seems not to answer. A network that doesn't
  reach the internet (a hotel's login page) blames no mint either, and one the system blocks for the app (Data Saver,
  Doze) counts as offline. Back online, an error that came from being offline is no longer shown. Adding a mint and
  moving money no longer show the network's own error text in English.
- For brands: a new brand term, `claim_own_phone_offline`, and new strings `send_offline`, `note_reclaim_offline`,
  `claim_unreachable`, `add_mint_offline`, `mint_offline` and `money_offline`. `add_mint_error_unreachable` and
  `money_error_network` take no argument any more; `home_offline`, `claim_offline`, `receive_invoice_offline` and
  `send_problem_unreachable` say new words. For developers: `Wallet.connection` (`Connection`: `Offline`,
  `Unverified`, `Online`; the constructor takes it in place of `online`), `Wallet.offline` (one state for every
  screen, kept up to date in the constructor's new `scope`) and `Wallet.followOffline` (a screen's value and its
  return, in one collector), `AppPreferences.InMemory` for tests;
  `InvoiceProblem.NoAnswer`; `AddMintError.Unreachable` and `MoneyMoveError.Network` carry no detail.

### Changed

- A refresh is faster. It asks its mints at the same time, one lane per mint domain, and each mint for several keys
  of an address at once (`scan.lookups.at.once`, 8), as a restore does; a 3-account wallet on the regtest stack
  refreshes in about half the time. A mint that doesn't answer, refuses or changed its signing key holds up only its own
  lane. A mint that fails one lookup among several is asked one at a time for the rest of that refresh, scan or restore,
  on every chain and account; the next one tries several at once again. A refresh still asks only the mints the
  accounts use now: their home mints, the mints of their addresses and the ones they hold money on.
- A mint whose signing key doesn't match is no longer asked for any of the wallet's keys on a refresh, only for its
  signing key, on every refresh: once it matches again, the mint is used again without a restart. A mint whose key
  matches is still compared once per app session. Comparing the key catches a mint that changed it; it doesn't stop
  someone in the mint's place who presents the mint's own key.
- "Scan for notes" also looks up the notes the wallet already holds on each mint, after looking for new ones: a note
  another phone with the same recovery phrase spent leaves the balance. The scan page counts them on the mint
  ("Checking note 3 of 10…"). For brands: the new brand term `scan_mint_checking_notes`. The recovery phrase is meant
  for one phone at a time: a refresh never looks for notes another phone made; a scan or a restore finds them.
- One way to say a mint is down, on every screen: it "isn't answering", or "No answer from" it, in place of
  "can't be reached", "couldn't reach" and "not reachable". Home no longer shows "Could not refresh:" with the
  network's own error text: it says the phone is offline, or that the update didn't go through, with "Try again".
  For brands: `home_refresh_failed` is gone, in its place `home_offline`, `home_update_failed` and `home_try_again`;
  `receive_invoice_unreachable` is `receive_invoice_offline`. For developers: `InvoiceProblem.Unreachable` is
  `InvoiceProblem.Offline` or `InvoiceProblem.MintNotAnswering`, and `HomeState.error` is `offline` and
  `refreshFailed`.
- For brands: the core modules have brand terms too, not only the feature modules. White Wallet's design system names
  the mint and the note in 11 strings, which no brand was told about and `just new-brand` left out:
  `release_note_old_keys_title`, `release_note_old_keys_body`, `received_key_changed`, `received_refused`,
  `received_refused_hours`, `received_refused_days`, `received_unreachable`, `received_unreachable_hours`,
  `received_unreachable_days`, `received_lightning_no_key` and `received_lightning_not_a_note`. They are listed in
  docs/WHITE_LABEL.md now, and a new brand gets them; a brand that says other words overrides them in its own strings.

- The empty History says what shows up there ("No payments yet…") instead of repeating the wallet tab's hint, which
  without a mint suggested an address that doesn't exist yet; the wallet tab drops that hint while it asks for a
  mint. The empty Notes view says what a note is.
- Adding a mint starts on one the wallet doesn't have yet: the default mint, else the next suggested one, else the
  empty field to type one. It started on the default mint even when the wallet had it already.
- For brands: `money_move_no_mints` names the mint now and is a brand term, with `money_move_no_mints_add`,
  `money_change_no_mints` and `money_change_no_mints_add` beside it; new brand terms `mints_empty`,
  `settings_no_home` and `settings_no_home_action`, and `notes_receive`. `home_empty` is no longer a brand term. For
  developers: `NoHome` moved to `whitewallet.core.wallet`, with `Wallet.noHome`.
- For developers, with one draft left: `KeySet` is `KeySet.Wallet`, `KeySet.Change` or `KeySet.Share(n)`
  (`KeySet.share(n)` and `KeySet.isAddress` are gone), and `KeyId` holds its `KeySet` in place of `purpose` and
  `share`. `PayDestination.PayLink.transfer` (`InternalTransfer?`) is `internalTransfer`, a `Boolean`.
- For developers, in `wallet.properties`: how long the wallet asks a mint often about a note it didn't know is a
  setting, `unknown.note.grace.period.millis` (by default a day, as before). The two intervals are renamed after it:
  `unknown.note.check.interval.first.day.millis` is `unknown.note.check.interval.in.grace.period.millis`, and
  `unknown.note.check.interval.after.first.day.millis` is `unknown.note.check.interval.after.grace.period.millis`. The old
  keys are no longer read. The comment of each time setting says its default and bounds as durations (`5m`, `1d`),
  checked by the build.

### Fixed

- Other paths still asked a mint whose signing key no longer matches about notes, and took its answers, most of which
  carry no key: a refresh turned a note set aside as unknown into a spent one, and closed a sent link as claimed;
  a kept link went to "spent" and lost its spend; renewing a note there looked it up first, and took it out of the
  balance when the answer called it used; the keys page (developer options) did the same. A note is now
  only looked up at a mint whose key was compared first, on every path: such a mint's notes, links and kept links
  stay as they are until the key matches again or the new key is accepted. A link on such a mint shows what it is
  worth as offline: what its certificate proves under the key the wallet knows, else what the link states, never what
  that mint says; the keys page says the key doesn't match and turns off what would ask the mint.
- For developers: `dev/check-lookups.sh` (in `just check` and CI) fails on a note lookup that skips the key check;
  `KeyCheckedMint` is what `KeyProber`, `IndexDiscovery` and `Pinning.lookup` take. `KeyScanResult.keyChanged`,
  `Wallet.recheckKey` returns null when the key doesn't match, `ClaimPreview.KeyChanged.amountMsat` is now
  `certifiedMsat` (nullable, as `ClaimPreview.Offline`'s), `KeyLookup.KeyChanged` carries no amount.
- Checking notes (one note, or all of them in the notes view) asked a mint whose signing key no longer matches about
  them, and its answer that a note was already in use took the note out of the balance: that answer carries no key, so
  a server with another key in the mint's place could do so. The key is now compared first, and such a mint's notes stay as
  they are, as on "Scan for notes", which looks up the notes it holds only on mints whose key matches. A refresh no
  longer checks the Lightning addresses on such a mint either.
- A refresh, or checking a note, settled an operation waiting for an answer from a mint whose signing key no longer
  matches, from answers that carry no key or another one. Such an operation now stays waiting for an answer until the
  key matches again or the new key is accepted.
- "Scan for notes" stopped with an error when a mint whose signing key changed answered with a note: the scan threw
  instead of going on with the other mints. That mint is now listed as one the scan didn't get through, as one that
  doesn't answer is.
- Receive made invoices on a mint whose signing key no longer matched the one the wallet knows, so a payment became
  money the wallet wouldn't spend there. Receive now asks each mint for its key as it opens and says so before the user
  tries, with the way to the mint's details, where the new key can be accepted; Send and Receive lists show such a mint
  but don't let it be picked, and its row on Home offers only Details. Under a Lightning address on that mint, Receive
  says payments wait there until the key matches again or is accepted, and the mint's page says the same. For developers
  and brands: `InvoiceRequest.KeyChanged`, `Wallet.mintsWithChangedKey` and `Wallet.checkMintKey`; new strings
  `receive_on_key_changed`, `receive_address_key_changed`, `receive_invoice_key_changed`, `receive_key_changed_action`,
  `send_from_key_changed`, and the brand terms `receive_address_key_changed_unnamed` and
  `receive_invoice_key_changed_unnamed`; `mint_key_changed` says it anew.
- A payment or note left to the wallet could still be made from a mint whose signing key doesn't match: tried there
  first, it failed though another mint could pay. Such a mint is now passed over, and a payment that finds the key
  changed before anything was sent goes on to the next mint, as for one that doesn't answer; with the money only
  there, Send says the key doesn't match instead of "not enough money".
- Home only knew of a changed signing key once something since the app started had asked that mint. The outcome is
  now saved, so Home knows from its first screen, and the first refresh of each app start compares the key of every
  mint the account holds money on (the same request that asks whether it answers). Money there stays in the total,
  with a line under it ("₿298 of it can't be spent right now") that leads to the mint; its row says "Signing key
  doesn't match" and the card shows as for any changed key. Send and "Send as a note" say the most one mint can send,
  and, apart from it, what can't be sent from that mint until its key matches, with Details; with nothing else to send
  from, that is all the form shows. Receive keeps "Create invoice" waiting, with "Checking mint.example.com…", until
  it has asked the mint for its key. For developers and brands: `Wallet.heldWhereKeyChanged`; `MintTrustStore` keeps
  the outcome (`changedKeys`, `setKeyChanged`; an own implementation needs both); `Wallet.sendableByMint` and
  `sendableNoteMsat` leave such mints out; new strings `home_mint_key_changed`, `home_key_changed_part`,
  `home_key_changed_part_title`, `receive_key_checking`, `send_key_changed_held`, `send_key_changed_action`, and the
  brand terms `home_mint_is_home_key_changed`, `receive_key_checking_unnamed` and `send_key_changed_held_unnamed`.
- A restore from the recovery phrase could miss whole accounts. It stops at the first account that never received
  money, yet the wallet let an account be added after an empty one; and it judged that one mint at a time, so an
  account used on another mint alone stopped it too. An account is now added only once the one before it has received
  money (BIP-44's rule; Settings → Wallets says which account to use first), a wallet that already has such an empty
  account says which accounts a restore misses until it receives money, and adding a mint or "Scan for notes" looks
  through the wallet's other mints again for the accounts it finds. For developers: the rule follows
  `scan.stop.after.unused.accounts`; new `CreateAccountResult.UseFirst`, `Wallet.accountToUseFirst`,
  `AccountSummary.used` and the strings `wallet_add_account_use_first`, `wallet_account_hides_later` and
  `account_name_use_first`.
- The app could close while adding a mint (or on any other lookup after a dropped connection, with another mint's
  connection still open): the retry closed the open connections on the screen's own thread, which Android forbids.
- A home mint removed under Settings → Mints came back at once, no longer home and no longer trusted, with whatever
  signing key it showed then: its page, reloading as the home changed, added it again. Looking at a mint's page no
  longer adds a mint the wallet doesn't have; it only stores the fresh fees and limits of one it has, and doesn't wait
  for a refresh to do that.
- Removing a mint or an account did nothing visible while the wallet was busy (a refresh, which opening Settings or an
  account's page starts, or a mint being added), and leaving the page then dropped the removal. The question now stays
  and shows that it is working until the wallet gets to it, and the removal goes through even if the page is left.
  "Remove this account" opens its question at once instead of waiting for the refresh. A refused removal no longer
  says "Can't be removed" twice on the mint's page.
- A renew, combine, split or claim whose answer was lost, and whose resend the mint then refused (rate limited, busy,
  a proxy's error page), counted as refused: its notes stayed in the balance although the first request may have spent
  them. It now waits for an answer and is settled by looking the notes up, as when no answer comes at all.
- An error page from something in front of a mint (a proxy's 502 or 504) after a renew, combine, split, claim or
  payment counted as the mint's refusal, so a payment could show as failed while the mint was still paying it. Only
  the mint's own answer counts now; anything else waits for an answer and is settled by looking the notes up.
- The wallet read "spent" or "unknown" anywhere in a mint's refusal as a fact: "locktime not reached: the note cannot
  be spent before …" made a note spent, and a received note the mint refused in such words lost its link for good.
  Only a mint that says `already in use` when asked about the note now shows it spent: such words make the wallet ask,
  a received note keeps its link and is tried again (archived after a week, as for other refusals), a payment waits
  for that answer before it counts as paid, and Claim says the mint doesn't accept the note right now, with the
  mint's answer, instead of "This note has already been spent." or "Not a valid note".
- Any page a Lightning address registration got back counted: a captive portal's or other web page as "registered",
  so checking an address could call it yours, and a proxy's error page as "refused", which handed the address's share
  to the next one although the registration may have gone through. Only the mint's own answer counts now; anything
  else is settled by asking the mint about the address.
- Claim offered a script note as spendable whose script consensus leaves open to any reading (a leaf version other than
  `0xc0`, an `OP_SUCCESS` opcode, a signature checked against a key of an unknown type), which LUD-25 has a mint refuse.
  Such a note now shows as not spendable. A note already kept stays kept, and its mint decides when it is claimed.
- A link or address whose host only started like the device's own address (`127.example.com`, `0127.0.0.1`) was
  fetched over cleartext http. Only a real loopback address (`localhost`, `127.x.x.x` written plainly, `[::1]`,
  `0.0.0.0`) or a `.onion` host may use http now, `lnurlw://[::1]:<port>` links included, which got https before.
- A payment answered "busy, try again now" (HTTP 503 with `Retry-After: 0`) was sent to the mint a second time by the
  HTTP library on its own, so an invoice could be paid twice. Every request now reaches a mint at most once; such an
  answer waits and is settled by looking the note up.
- Looking a note up read an error page from something in front of a mint (a proxy's 502) as the mint's answer, so a
  page saying `already in use` could mark a note spent or a payment paid. A mint's lookups, discovery and pay request
  now count only its own answer; anything else is no answer, asked again later.
- A mint on the next LUD-25 draft answers a lookup of a spent note, or one being paid out, with a value of 0. Read as
  a note, a payment the mint had made looked failed and its note came back into the balance. A lookup answering 0
  now means spent or being paid, and only from a mint whose signing key matches: a payment waiting for an answer
  settles through the mint's `verify` link (LUD-21), which the wallet keeps with it, and a received link answered 0
  shows as gone but keeps its spend, is claimed should its value come back, and goes to the archive after a week.
  Notes worth 0 aren't supported, as before: they can't be told from spent ones, and no mint makes them today. The
  database update (version 11) adds the `verify` link. Mints on lnurl-mint 0.13.1 answer as before.

## [0.9.0]

### Added

- The launcher icon is in the APK as PNGs too, drawn by the build from the brand's adaptive icon. Android shows the
  adaptive icon as before; the PNGs are for app stores that read the APK and can't draw it. Zapstore doesn't draw a
  vector with a clip path, and showed another image from the APK instead. A brand has nothing to do; one with its
  own `res/mipmap-*dpi/ic_launcher.png` keeps those (see §2 of `docs/WHITE_LABEL.md`).
- An archive for received links the wallet no longer tries (Notes → "Archived"). A link goes there a week after its
  claim first failed, whether its mint refused it or couldn't be reached (before, only refusals counted, and the others
  were tried forever and couldn't be removed). The user can archive a
  kept link at any time too. From the archive a link can be tried again or removed for good, after a warning that the
  recovery phrase can't bring it back. Each says why it is there. Links refused for a week under an earlier version
  are in the archive after the update. An account with links in its archive can't be removed until they are tried
  again or removed there: they are only on this phone.
- Withdraw links of other services (LNbits vouchers, ATMs, faucets: `lnurlw://`, or an `LNURL1…` that is one) open in
  Claim, also from other apps, and are received over Lightning into the home mint: one confirm, which says what the
  link pays and about what arrives after the mint's fee. The most both the link and the mint allow is taken; a link
  outside what the mint takes is refused with the range it takes. An account without a home mint picks one of its
  mints for that link. Offline, such a link isn't kept: open it again when connected. A link whose `k1` looks like a
  note's spend is first asked by its key only; if its service doesn't answer for it as a note, the user is asked
  before the link is shown to it. Never at a mint the wallet holds: such a note is kept like one whose mint can't be
  reached (a deploy's error page, a challenge page), and whoever answers in the mint's place never gets its spend. Kept, such a link waits under received notes with "Receive"; each refresh asks
  its key again, and it is claimed at its mint once that answers for it as a note, so a passing trouble with the mint
  doesn't send a note over Lightning. A link the service refuses waits there too, with "Receive" to try again. The screen is titled "Receive over Lightning", and History names the link's service ("Received from …").
- Notes from mints without a signing key (LUD-25 only says a mint SHOULD publish one). Their notes can't be certified,
  and a swapped mint can't be noticed, so the wallet never adds such a mint nor keeps money there: a note from one is
  received into the home mint over Lightning once the user agrees to the fee. If that payment fails (or the note was
  kept while offline), it waits under received notes with "Receive" to try again. A new mint that can't be asked for
  its signing key isn't added (it is unreachable for now), so it can't hold money before the wallet knows it has one.
  A mint the wallet holds that stops showing its signing key, or shows a broken one, counts as a changed key: nothing
  is sent there, it can't be made home, and its page says so, with no key to accept until it matches again. The
  texts about a changed key now say the signing key "doesn't match" the one the wallet knows, which fits both
  (`mint_key_changed`, `mint_key_changed_short`, `home_key_changed_title`, `home_key_changed_body`,
  `claim_key_changed`, `claim_kept_key_changed`, `received_key_changed`, `notes_key_changed`, `move_stayed_key`):
  a brand with its own words for them rewrites them.
  New brand terms `claim_melt_not_a_note`, `claim_melt_into_home`, `claim_melt_pick`, `claim_melt_no_mint`,
  `claim_kept_for_lightning`, `claim_melt_no_signing_key` and `onboarding_error_mint_without_key`; also
  `received_lightning_not_a_note` names the note.
- A link from another app that the wallet can't use says so on Home (before, nothing happened).
- Links in capitals (`LIGHTNING:`, `LNURLW:`, `LNURLP:`), as QR codes write them, open the wallet too.

### Changed

- An `LNURL1…` link from another app or the scanner first shows "Opening link" while its service says what it is,
  then opens in Claim (a withdraw link) or Send (a pay link). Send hands a withdraw link pasted there to Claim.
- Home's card about notes a mint doesn't know answers a tap on "Check again": a short message says the notes are
  back, that the mint still doesn't know them, or that it couldn't be reached (before, nothing showed unless the
  notes came back). The card says when the mint was last asked and that there is nothing to do but wait or ask the
  mint's operator, and "Show notes" opens the Notes tab in an app with one. New brand terms `home_unknown_help`,
  `home_unknown_show`, `notice_unknown_back`, `notice_unknown_still` and `notice_unknown_unreachable_unnamed`: a
  brand with its own words for the mint or the note rewrites them (see `docs/WHITE_LABEL.md`). Also new:
  `home_unknown_asked`, `home_unknown_asked_now` and `notice_unknown_unreachable`.
- Home says what the notes handed out as links and not claimed yet are worth, under the balance ("₿3,000 shared,
  not claimed yet"): the money left the balance when the note was made, and is the user's to take back until
  someone claims it. A tap opens the Notes tab, or History in an app without one. New strings `home_shared_total`
  and `home_shared_title`; a brand that overrode `notes_waiting_total` and `notes_shared_title` words these alike.
- The account switcher lists the accounts of the wallet in use. Other wallets' accounts show after a tap on
  "N other wallets" (`accounts_other_wallets`), so changing to another recovery phrase is a step of its own.
- Home shows the balance per mint under the total once some of an account's money is outside its home mint: the
  home mint first (also when empty), then the others by amount, each row leading to the mint's page. Up to four
  mints have a row; with more, three do and a last row ("4 more mints", with what they hold together) opens the
  account's mints. A payment is made from one mint's money, so the total alone promised more than could be sent
  at once. Money on the home mint alone, a hidden balance or `mints.visible=false` show the total alone. New
  brand terms `home_mint_is_home` and `home_more_mints`.
- Send says, before a destination is entered, how much one payment can be when that is less than the balance
  ("You can send up to ₿6,000 of your ₿8,000 in one payment…", `send_limit_one_payment`).
- The account in use is its mark in Home's top bar, on every tab, in place of the chip under the balance; a tap
  opens the switcher. With several wallets, a line over the balance names the wallet and the account ("Wallet 2 ·
  Main", `home_account_in_wallet`). Where the mark shows, it has the top bar's Refresh button's place: the page
  is pulled to refresh, and a screen reader has "Refresh" as an action of the page. An app of one account keeps
  the button.
- The regtest stack has four mints on `localhost:8111` to `8114`, mint N on port 811N, and the faucet on `8110`
  (it was on `8114`, the fourth mint on `8115`). The second mint charges the 21 sat address fee; the fifth mint,
  which did, is gone, and with it `regtest.sh note --mint5`. Run `regtest.sh reset` on both stacks: accounts on
  the old `localhost:8115` and `8116` stop working.

### Fixed

- A mint whose signing key doesn't match stopped the whole refresh as soon as a payment to one of its addresses came
  in: the other mints' address payments, the shared notes and the note strategy, and every account after it, waited
  until it matched again. Now only that mint is skipped, and Home names it as before.
- Paying, handing out a note, taking one back, splitting, combining, renewing and moving to another mint said
  "couldn't connect" at a mint whose signing key doesn't match; they now say the key doesn't match, as the other
  screens do (new brand terms `send_problem_key_changed` and `notes_problem_key_changed`). Moving money to another
  account counted a mint that shows no signing key as movable, and the move then kept the money there; the check now
  says it stays. Its page no longer offers to make it home, which could only fail.

## [0.8.1]

### Fixed

- The QR scanner was titled "Scan for notes" in 0.8.0: the new page's title had the scanner's string name
  (`scan_title`), and the build kept only one of the two. The page's is now `scan_notes_title`; a brand that
  overrode `scan_title` for the page renames its override, and one that meant the scanner's has its text back.
  `dev/check-resources.sh`, in `just check` and CI, fails on a resource name defined in two modules.

## [0.8.0]

### Added

- LUD-25/26 at luds `92e430f` (lnurl-mint 0.13), next to the draft `2f17578` (lnurl-mint 0.12): the wallet asks each
  mint which it speaks. New notes use BIP-32 keys, one account `S/a'` at a time. Notes and addresses from before move
  to the new keys by themselves on a refresh, for free; a mint upgraded later is noticed. A restore finds both.
- One share per Lightning address on a current mint, so addresses of an account can't be linked by their keys.
  Whether an address is yours is now proven to the mint, without changing anything there.
- Address fees: a mint may charge for an address. Setup shows the fee and lets the user reserve the name, pay later or
  go on without one. A reserved name is on the addresses screen until paid or expired. The address form says what
  the mint charges before "Use this address", not only after it.
- Payments to someone on the same mint go through the payee's pay callback, for free and with nothing pending.
- Wallets of several accounts: a recovery phrase can hold several accounts, each with its own money and addresses,
  and the app can hold several wallets. The switcher groups them; backup and delete act on the wallet, and delete
  names the accounts it takes with it. A restore finds a wallet's accounts on that mint, up to the first unused one.
  On a legacy mint only a wallet's first account can have an address. Database version 5.

- A wallet without a mint: setup may go on without one where the brand lets users add a mint later
  (`mints.changeable=true`, `mints.visible=true`). Home asks for a mint until there is one, and Receive points there;
  the first mint added becomes its accounts' home, and finds what the phrase holds there.
- Settings → Wallets: every wallet on the phone, then a wallet's page (its name, recovery phrase, mints, accounts,
  "Add account", deleting it) and an account's page (its name, Lightning addresses, mints, notes, note settings).
  On top of Settings the account in use opens its page directly, and "Wallets" is the row below, in every app.
  Wallets and accounts can be renamed; a wallet is "Wallet 1", "Wallet 2" by age until it has a name. The pages show
  everything of the wallet and account in use; another account offers "Use this account" first. "Add wallet" is
  here now; with a single wallet, "Wallets" opens that wallet's page at once, "Add wallet" on it. A rename opens
  with the keyboard up and the name selected.
- "Add account" looks for what the new account already holds on each of the wallet's mints before it opens, and
  shows how far it is: its place under the recovery phrase may have been used before, on another phone or by an
  account removed here. If a mint doesn't answer, no account is added and the screen names that mint; try again
  later. The suggested name is "Account 2", "Account 3", …; the new account's page opens afterwards.
  An address derived from the recovery phrase that is still registered to the account is listed again.
- "Scan for notes" on a wallet's page: looks through the wallet's keys at each of its mints again, as a restore
  does, and adds what it finds; each mint says when it was last scanned. A scan that got through ends with "Done"
  ("Scan again" below it); one that stopped offers "Scan again" or "Later". A scan that stopped, or a mint the wallet
  met without one (a note claimed from it, money moved there), is remembered: the main screen says "Scan not
  finished" until a scan gets through, and the wallet's page too. After an update, mints other than an account's
  home mint count as not scanned once. A mint whose scan stops while it is added stays added, with "Try again"
  right there or "Later".
- Remove an account (its page, in a wallet with more than one): only while it holds no money and nothing of it is
  unfinished. Its history and settings go from the phone; its Lightning addresses stay registered and are named
  before it goes. The next account added takes the freed place and finds what was paid there meanwhile.
- The seed-derived default address of a new account comes from its first share, so each account of a wallet has its
  own; those made before are still found by a restore. The watch-only key moved from the mint's page to each
  address: a key only ever shows what was paid to its own address.

### Changed

- A page that takes more than a moment to read what it shows (0.4 s) now says so with a spinner, which then stays
  for half a second at least, so it never blinks; a page that is ready sooner shows nothing in between, as before.
  Home's loading screen follows the same rule: it no longer blinks when the work ends just after it came up.
  Progress bars and spinners for work on a page (the notes, a mint's page, note settings) follow it too, and a slow
  start of the app is one wait instead of the balance between two.
  "Add a mint", "Change home mint" and "Move money" show their title from the start.
- Each wallet has its own mints: the signing key it pinned there and whether you trust a mint are the wallet's, and
  another wallet on the phone never sees or changes them. A mint's new key is accepted per wallet. An update gives
  every wallet the mints and trust choices the phone had. Database version 6.
- A home mint can be removed like any other mint, once nothing of the wallet is left there: the accounts that had it
  have no home until the user picks one, which Receive asks for.
- A refresh, in the app or in the background, looks after the active wallet only. Another wallet's payments,
  received notes and open operations are taken care of once it is active.
- Simpler setup. The first screen only offers to create a wallet or restore one (a phrase, nothing else). Then the
  wallet's mint: pick it, see what it says about itself (fees, limits, address fee, signing key) before anything is
  saved, and add it; a restored wallet shows, account by account, what it finds there. Then a Lightning address on
  that mint, which a restored wallet gets back by itself where it can; it can always be skipped. A closed app goes on
  where the setup was, and back on its first step gives the setup up (not for a new wallet that already holds money
  and isn't backed up). Adding a mint from Home or Settings → Mints goes through the same steps. Adding an account
  only asks for its name: it is on every mint of the wallet at once. A restore left without its address by an older
  version opens on the main screen.
- The home mint is each account's own choice among its wallet's mints: the mint you trust most for that account's
  money, where its invoices are made and where collecting moves its notes. So one account can collect at one mint and
  another account at another. Changing it is a setting; it no longer needs an address there, the old home stays
  trusted, and your addresses stay where they are. A new account starts with the home of the account in use. An update
  keeps every account's home.
- Turning collecting at the home mint on first shows what it would move now and at what fee. It stays each account's
  setting, and it, like every automatic step, only touches the active account's notes.
- New account setting: pay from other mints first, so money gathers at the home mint.
- Any address can be the primary one, on any mint, and the primary one can be unregistered, which leaves none.
  Receive offers the primary address, else one on the home mint, else an invoice, and lets you pick another. The main
  screen warns about any address that no longer reaches the account. The account switcher shows balances only; it
  switches, moves funds and links to Settings → Wallets, where accounts and wallets are added.
- Derived keys (developer mode) are on the account's page instead of under Developer options.
- Deleting says "wallet" throughout: it always removed the recovery phrase with all its accounts.
- The database checks that every row belongs to an account and every account to a wallet. An update keeps notes,
  received notes and operations of an account that is gone aside instead of dropping them, and merges a duplicate
  account. Database version 7.

### Fixed

- A mint's own name (`mint`) was offered as free on a LUD-26 mint.
- After a mint's upgrade to LUD-26, a payment to an address on that mint failed ("This invoice is from your own
  mint") until the app was restarted: the wallet still took the mint for a legacy one. A legacy mint is now asked
  which protocol it speaks every time. The first refresh after the upgrade also says what arrived on the address's
  old keys, as the Receive screen expects.
- The address form showed a random name, and in a setup the brand's first mint, for a moment before the right ones.
  It now shows once the mint and the name are known, and in a setup stays locked while the mint is asked whether the
  wallet already owns an address there.
- Screens showed something else for a moment before what they are about: adding a mint showed the brand's mint as
  text before the list to pick from, Receive the amount form before the address, Settings "no address" and no mints,
  switches off before on, lists empty before filled, a total of nothing before the notes. Every screen now draws
  nothing until what it shows is read from the phone.
- A note claimed from a mint the wallet hadn't met started at the wallet's first key there, whatever keys its phrase
  had already used on that mint, and left the notes on them unfound. The mint is now connected before the claim, so
  the claim takes the first free key and the notes on the wallet's keys there are found.
- A restored wallet taking back an address it had paid for was asked about the mint's address fee again, and a brand
  with `addresses.fee.onboarding=skip` left it without that address. A name that is the wallet's already costs nothing.
- Adding a mint finds what every account of the wallet already holds there, as a restore does on its mint, and creates
  the accounts this phone didn't have yet. Payments to addresses found there keep arriving. A scan that fails says so,
  and adding the mint again goes on from there.
- A lookup on one mint no longer marks a note of another mint on the same domain as unknown or spent.
- A scan of one mint started past the keys the wallet knew at another mint on the same domain, and so missed
  earlier keys used only at the scanned mint.
- A refused payment says why: an invoice of your own mint, one already used, or else the mint's own words. A bare invoice
  whose payment failed no longer offers "Try again", which a mint always refuses.
- The developer key screen has a tab per address, so address notes on new keys can be scanned and renewed there. Its
  Change tab could show the count of addresses instead of the change index.
- After a restore, new payments to an address were only found once its name was entered again. Every share the account
  handed out is now scanned on a refresh, on its home mint, whether or not the phone knows the name.
- A payment, a move or a combine of more than 100 notes was refused by the mint: all their notes went into one request.
  They now merge 10 notes per request.
- An address the mint refused at registration, a name held for someone else's fee say, used up a share, which left a
  gap a restore had to look across. The next address now gets that share.
- A restore, or adding a mint, looks keys up several at a time, so it waits for far fewer round trips to the mint. It
  finds and stops at the same keys as before.
- A restore stopped after 3 unused shares, so money on an address after three that were never paid was missed. It now
  stops after 20.

### For developers

- `dev/check-flash.sh` (in `just check` and CI) fails on a screen state that starts with a value the screen would
  draw before the real one: start it with `null` or `loaded = false` (see "No flashing screens" in
  `docs/DEVELOPMENT.md`).
- Debug builds in developer mode can get a note from any local regtest mint on "Claim a note" (the faucet makes it),
  whichever mint the account is on: a note from a mint the wallet hasn't added is one from an unknown mint. From the
  terminal, `regtest.sh note --open` opens a note in the app, and `--mint4`/`--mint5` join `--mint2`/`--mint3`.
- `pageLoaded`/`screenLoaded` (`core/designsystem`) gate every page that reads what it shows, and draw the loading
  indicator when it is due; `showsWorking` does the same for the sign of work on a page that stays; `loading.indicator.shown.after.millis` and `loading.indicator.shown.at.least.millis` in
  `wallet.properties` set its times.
- `wallet.properties` holds the numbers that decide how the wallet behaves, such as how far a restore or adding a mint
  looks (`scan.*`) and how many notes one merge request spends. A build can override one with `-P` or a `WW_` variable, a release build can't.
  Brands don't set these (see "Wallet settings" in `docs/DEVELOPMENT.md`).

### For brands

- New anchor `PageLoading`: the picture of a page that is still loading (default: a spinner). A `Decoration` with an
  `else` branch needs no change.
- New keys: `wallets.max`, `addresses.fee.onboarding` (`ask`, `reserve` or `skip`), `addresses.fee.max`.
- `addresses.lightning=required` is gone: an address is never needed to set a wallet up (`optional`, the default, or
  `none`).
- `accounts.max` now counts accounts per recovery phrase, not per app. A brand that wants one account in all sets
  `wallets.max=1` and `accounts.max=1`: `wallets.max` defaults to no limit, so with `accounts.max=1` alone users can
  still add wallets.
- `Wallet.onboard` and `createAccount` take `inActiveWallet`; `OnboardResult` has `AwaitingFee`, `FeeRefused` and
  `MintTooOld`, so an exhaustive `when` over it needs them. `deleteActiveAccount` deletes the whole wallet.
- `KeySet.LegacyWallet`, `LegacyChange`, `LegacyAddress` and `LEGACY` moved to `whitewallet.core.wallet.legacy`.
  `Note` and `OutputRef` default to `KeyScheme.Bip32`; `Requests.servicePayUrl` asks for `mint`, not `_`.
- New strings, for brands that word their own: `address_fee_*`, `onboarding_error_fee*`,
  `onboarding_error_mint_too_old`, `accounts_wallet`, `accounts_add_wallet`,
  `delete_accounts`. `delete_what` and `accounts_separate_backups` say something new.
- `Account` has no `host` and `Profile` is gone: `Wallet.homeHost`, `WalletStore.wallet(id)` (a `WalletRecord`) and
  `Wallet.primaryAddress` say what they said. `changeHome` is `setHomeMint(host, lines)`; `UnregisterResult.IsPrimary`
  and `MoneyMoveError`'s address cases are gone.
- New strings: `receive_other_address`, `receive_use_address`, `notes_pay_from_others_first*`,
  `notes_collect_confirm*`. Gone: `mints_home_mint_of`, `addresses_primary_hint`, `money_name_*`,
  `money_address_preview`, `money_review_address`, `money_result_address`, `money_error_name`,
  `money_error_registration`, `money_error_address_fee`, `money_error_mint_too_old`. `money_change_body`,
  `addresses_intro`, `addresses_add_elsewhere_hint`, `receive_no_home` and `home_address_inactive_*` say something new.
- Setup: `Wallet.createWallet(name)`, `createAccount(name)` (no mint), `claimAddress`, `previewMint` (writes nothing),
  `addMint(host, expectedKey, onProgress)`, `heldAt`, `setupStep`/`setSetupStep`, `abandonSetup`. Gone:
  `Wallet.onboard`, `createAccount(host, …)`, `needsAddress`, `abortRestore`, `forgetPreparedPhrase`, the
  `requireAddress` parameter, `AddressPolicy.Required` and `OnboardResult.AccountLimitReached`. `OnboardingMode` is
  `Setup`, `ChangeAddress`, `AddAddress` or `AddAddressElsewhere`; `RestoreScreen` only takes the phrase.
- New strings: `add_mint_*` (brand terms where they name the mint), `setup_abandon_*`, `setup_address_*`,
  `welcome_create`, `welcome_restore`, `account_name_body`, `account_name_limit`, `scanned_account_name`, and
  `mints_add` names the mint now (a brand term). Gone: `onboarding_start`, `onboarding_restore`,
  `onboarding_restore_instead`, `onboarding_new_account_body*`, `restore_default_account_name`, `restore_searching`,
  `restore_skip_mint*`, `restore_error_mint`, `restore_error_network`, `restore_address_*`, `restore_cancel*`,
  `mints_add_field`, `mints_add_hint`, `mints_add_offered_hint`, `mints_add_unreachable`, `mints_add_invalid`,
  `regtest_add_account`.
- `TextFieldParams.focusSelected` (design API): the field takes the focus when it appears, its text selected. A
  brand that draws its own text field should honour it; ignoring it only loses that convenience.

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
