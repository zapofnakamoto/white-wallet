# White-labeling

A fork rebrands the app by editing **only** `brand/`. No Kotlin changes are needed; a brand that wants more (its own
look in code, its own pages, its own note strategy) adds a `code/` module there (see "Design" and "Custom code").

## Quick start: generate a brand

```sh
dev/new-brand.sh --name "Acme Cash" --id com.acme.cash --mint mint.acme.com --primary "#0B6E4F" --logo logo.svg
```

This writes a complete brand directory, by default `brands/<name>/`, which git ignores so a generated brand never lands in this repository. Keep a real brand in its own repository (`--out ../acme-brand`) and build it with `-PbrandDir=../acme-brand`; `--out brand --force` replaces the default brand instead. `brand-examples/` holds only the committed samples. The script then checks the brand and builds a debug APK that installs next to the original. `--help` lists every option (`--unit sats`, `--lock-unit`, `--lock`, `--no-address`, `--max-accounts`, `--max-addresses`, `--note-strategy`, `--no-strategy-choice`, `--collect-at-home`, `--collect-fee-cap`, `--notes-view`, `--hide-mints`, `--developer-options`, `--trusted-mint`, `--suggest`, `--support-url`, `--secondary`/`--tertiary`, `--no-mint-check`, `--no-build`). It:

- refuses an invalid application ID or the original one, and warns about `com.example.*`, which app stores refuse;
- checks that `--mint` answers as an LNURLcash mint (a payRequest with `withdrawLink`, as the app does);
- derives the light and dark palette from `--primary`, with button text that always reaches 4.5:1 contrast, and warns if the primary is too pale (or, in dark mode, too dark) to read as a link or outline;
- turns an SVG or an Android vector drawable into the launcher icon, fitted inside the adaptive icon's safe zone, and the in-app logo. A single-color mark is drawn in the button text color on the brand color; a multi-color logo keeps its colors. Text, images, gradients and combined transforms are refused with a hint: convert them to paths first, or use Android Studio's Vector Asset tool and pass the `.xml`;
- writes `zapstore.yaml` and, per language, the brand-term strings commented out, ready to rewrite as whole sentences (see below);
- runs `dev/check-brand.sh` on the result.

Then edit the brand-term strings and `zapstore.yaml`, and see §4 to sign and publish. The sections below describe every file it writes.

## 1. `brand/brand.properties`

Keys are grouped by what they concern, the group first: `app.*`, `mints.*`, `accounts.*`, `addresses.*`, `amounts.*`, `notes.*`, `history.*`, `developer.*`. A key with two values reads as a statement (`mints.visible=true`); one with more lists them. A brand still using a key from before the grouping (`appName`, `showMints`, …) fails the build with its new name.

| Key | Effect |
|---|---|
| `app.name` | Launcher label and in-app title |
| `app.id` | Android package ID. Must be unique to your fork |
| `app.version.code`, `app.version.name` | Release version |
| `app.releaseStage` | How finished the app is: `auto` (default) = beta while `app.version.name` is below 1.0.0, stable from then on; or `alpha`, `beta`, `stable` as set. Alpha and beta show a badge beside the app's name (or the brand's wordmark) at setup and on the wallet tab, and by the version in About; setup and About add a sentence on what it means (`release_notice_alpha`, `release_notice_beta`; the badge says `release_stage_alpha`, `release_stage_beta`). Stable shows nothing |
| `app.appearance` | `system` (default) follows the phone's light or dark setting; `light` or `dark` always uses that scheme, for a brand with a single look. See "Design" |
| `app.supportUrl` | Exposed as `BuildConfig.SUPPORT_URL` |
| `mints.default` | LNURLcash mint preselected during onboarding (`host` or `host:port`) |
| `mints.suggestions` | Comma-separated mints offered in the onboarding dropdown (the default is always first; users can still pick "Custom…") |
| `mints.changeable` | `false` hides the mint field, locking users to your mint. With `true`, users can also add a Lightning address on another mint (same recovery phrase), change their home mint from the mint pages and move money between the mints they know, which needs `mints.visible=true` (when the home changes, an account that collects at its home mint takes along what the fee cap allows; others choose) |
| `mints.visible` | `false` hides everything about mints from users: Settings → Mints (the device's mints), Account → Your mints (the account's, with its actions) and the mint pages, on Claim which mint a note is on and whether it's trusted (a note whose amount can't be verified is still flagged), and in the notes view the mints' names, labels and totals: one mint's notes are a plain list, several mints' are only separated by a line. Default `true`. Note settings disappear by themselves when there is nothing to choose: one strategy that renews the address's notes itself (`single`), `notes.collect.atHomeMint` `hidden` or `always`, and `notes.maintenance.notices` `hidden` or `always` |
| `mints.trusted` | Mints shown as trusted from the start (Settings → Mints; a check mark on their notes, a hint when claiming), comma-separated `host:pubkey` with the mint's signing key (66 hex characters). Trust names the key: a mint presenting another key isn't trusted. Users can untrust them. Each account's home mint is trusted by default anyway. Default empty |
| `accounts.max` | How many accounts the app holds. `1` hides everything about accounts (no switcher, no "Add account"); at the limit, new accounts and restores are refused with a message. `0` = no limit (default) |
| `addresses.lightning` | Whether accounts get a Lightning address at setup. `required`: setup asks for a name and can't go on without one. `optional` (default): setup asks for a name, and users may "Continue without an address", also on the address step after a restore. `none`: setup only creates the account on its mint, which is a single "Get started" button when `mints.changeable=false`, and restoring skips the address step. Without an address, users receive by invoice (Receive → amount → QR) and can still get one later in Settings → Account → Lightning addresses. |
| `addresses.max` | Lightning addresses an account may register per mint. Default `1`; `0` = no limit. Developer mode can override it for testing |
| `amounts.unit.default` | How amounts are shown (until the user picks another unit under Settings → Amounts, if allowed): `bitcoin` (default, [BIP-177](https://github.com/bitcoin/bips/blob/master/bip-0177.mediawiki): the smallest unit as a whole number with the sign, `₿1,000`) or `sats` (`1,000 sats`). The numbers are the same either way |
| `amounts.unit.changeable` | Whether users may switch the unit under Settings → Amounts (default `true`). With `false` the page is gone and every amount shows in `amounts.unit.default` |
| `notes.view` | Where users see and manage their notes (share one as a QR code, split, combine, move, recheck, renew): `tab` (default) = a **Notes** tab next to Wallet and History, `settings` = a page under Settings → Account, `hidden` = nowhere; the note strategy keeps the notes by itself and users only see their balance and history |
| `notes.actions` | What users can do with their notes in the notes view, and with a handed-out one, comma-separated: `share` (the whole note as a QR code), `split`, `combine`, `move` (to another mint, and "Move anyway" when collecting skipped one; needs `mints.visible=true`), `recheck`, `renew`, `print` (a handed-out note on its page: its face, QR code and bearer warning on white paper, in the brand's light theme). Without `combine`, `move` and `recheck`, which work on several notes, there is no selecting. Unset (the default) = all of them, without `move` when `mints.visible=false`; empty = the notes are only shown. An unknown action, or `move` with `mints.visible=false`, stops the app at its first start |
| `notes.strategies` | The note strategies users can choose from under Settings → Account → Note settings, in this order: `single` (keep only one note per mint), `manual` (keep notes as they arrive), and any your code module adds (see "Custom code"). One entry hides the choice. Default `single,manual` |
| `notes.strategy.default` | The strategy a new account starts with; one of `notes.strategies`. Default `single` |
| `notes.collect.atHomeMint` | "Move everything to the home mint" in Settings → Account → Note settings: notes on other mints (claimed notes, or an old home mint) are moved to the account's home mint automatically, over Lightning, when the move costs at most the fee cap. `hidden` = not offered, `off` = offered and starts off (default), `on` = offered and starts on (users can switch it per account), `always` = every account collects and users have no switch. When users change their home mint, a summary shows what moves and its fee first; with `on` or `always` the cap decides, otherwise they choose per mint |
| `notes.collect.feeCap` | The default fee cap for those moves, in percent of what is moved (default `2`). Users pick 1, 2, 5 or 10 % |
| `notes.maintenance.notices` | A short message on the home screen when the wallet changed the notes by itself: combined, split or renewed them, or moved them to the home mint ("Combined 2 notes into one of ₿63. You got ₿1 in fees back."), with Details opening History's note maintenance. It shows while the home screen is up; after a claim or a payment, whose own screen says what happened, only if the balance changed. `hidden` = never, `off` = a switch under Note settings that starts off, `on` (default) = a switch that starts on, `always` = always, no switch |
| `notes.maintenance.notices.whenNotesHidden` | With `notes.view=hidden`, users don't know about notes: `true` (default) tells them what the balance did ("You got ₿1 in fees back."), and about moves to the home mint while mints are visible; `false` tells them nothing |
| `history.filter` | Filters on the History tab: `types` (default) = chips for received, sent, moved and note maintenance; `maintenance` = one chip that shows or hides note maintenance; `none` = no filters, and note maintenance stays hidden |
| `developer.options` | Developer options in Settings (developer mode, request log, derived keys): `shown` (default) = always; `unlock` = hidden until the user taps the version in Settings → About seven times, as on Android itself; `hidden` = never in release builds. A debug build treats `hidden` as `unlock`, and starts with developer mode on, so the options show there anyway |

### Naming the custodian and its notes, in any language

The app has two brand terms: the party that holds users' funds is a **mint**, and what it issues, the money users hold, claim and hand out, is a **note**. Most text is written without either word ("Couldn't connect", "Payment received"), so translations stay natural. Only the strings that can't do without them name them. They sit in one block marked *Brand terms* at the top of each feature module's `res/values/strings.xml`, ending at its first blank line. A brand made with `just new-brand` gets all of them, in every language, as a commented-out override in its own strings. Developer options and search keywords are not brand terms.

#### The mint

Keys say `mint` where the text does (`<screen>_<what it says>`):

| String | Default (en) |
|---|---|
| `claim_home_mint` | On %1$s, your home mint |
| `activity_moved_mint_other` | Moved to another mint |
| `notes_home_mint` | Home mint |
| `notes_mints` | %1$d mint / %1$d mints (plural) |
| `notes_move_title` | Move to another mint |
| `notes_move_target` | Mint, e.g. example.com |
| `notes_problem_different_mints` | Select notes on one mint. |
| `onboarding_mint` | Mint |
| `onboarding_mint_address` | Mint address |
| `onboarding_mint_custody` | Your funds are held by the mint you choose. Keep amounts small. |
| `onboarding_add_elsewhere_title` | Address on another mint |
| `onboarding_add_elsewhere_body` | A name for this account on another mint, with the same recovery phrase. Payments to it arrive as notes on that mint, in this account. Your primary address stays where it is. |
| `restore_error_mint` | This mint can't be used for a restore. Check that it's the one you used before. |
| `settings_home_mint` | Home mint |
| `settings_mints` | Mints |
| `addresses_home_mint` | Home mint: %1$s |
| `mint_change_home` | Change home mint |
| `mint_make_home` | Make this my home mint |
| `settings_account_mints` | Your mints |
| `mints_home_mint_of` | Home mint of %1$s |
| `mints_remove_mint` | Remove mint |
| `addresses_add_elsewhere` | Add an address on another mint |
| `settings_strategy_single_hint` | Combine all notes into one per mint. Free. |
| `mint_title` | Mint |
| `about_body` | A Lightning wallet that keeps your money as notes on a mint you choose. Your recovery phrase stays on this device. |
| `notes_collect_search` | Move everything to the home mint |
| `mints_intro` | Trust a mint by its signing key: notes from trusted mints are marked with a check. If a mint ever signs with a different key, it is no longer trusted. |
| `mints_add_field` | Mint, e.g. example.com |
| `notice_moved_home` | Moved %1$s to your home mint |

`brand-examples/acme/` is a complete brand that does this ("provider", in English and German), locks users to one mint, shows sats and adds a note strategy of its own (see "Custom code").

#### The note

German calls it "Schein". `brand-examples/acme/` says "banknote" and "Geldschein" (masculine like "Schein", so every German sentence keeps its grammar) and draws notes as banknotes (`NoteFace`, see "Design"). Some screens only exist with some settings: with `notes.view=hidden` the notes view's strings (every `notes_*` string of `feature/notes`), `home_tab_notes`, `settings_note_list`, `settings_note_list_hint` and the messages about notes (`notice_combined_into_one`, `notice_combined`, `notice_split`, `notice_rearranged`, `notice_renewed`) never show; with `notes.maintenance.notices=hidden` none of the `notice_*` strings do, nor `settings_notices_hint`. With `history.filter=none`, `history_filter_maintenance` shows only after a message's Details, and `history_show_maintenance` never. The rest can.

| String | Default (en) |
|---|---|
| `claim_title` | Claim a note |
| `claim_input` | Paste a note link |
| `claim_scan_hint` | Point the camera at the note's QR code. |
| `claim_check` | Check note |
| `claim_invalid` | Not a valid note: %1$s |
| `claim_spent` | This note has already been spent. |
| `claim_in_wallet` | This note is already in your wallet. |
| `claim_other_mint` | This note is held by %1$s and stays there. |
| `home_tab_notes` | Notes |
| `home_empty` | Nothing here yet. Share your address to get paid, or claim a note. |
| `home_wallet_empty` | Nothing here yet. Share your address to get paid, or claim a note. |
| `history_filter_maintenance` | Note maintenance |
| `history_only_maintenance` | So far only note maintenance. |
| `history_show_maintenance` | Show note maintenance |
| `activity_claimed` | Claimed a note |
| `activity_combined` | Combined %1$d note / Combined %1$d notes (plural) |
| `activity_split` | Split a note in two |
| `activity_sent_note` | Sent a note |
| `move_hint` | Funds stay where they are held and move into the other account's keys. Moving part of a note may cost a split fee. |
| `notice_combined_into_one` | Combined %1$d note into one of %2$s / Combined %1$d notes into one of %2$s (plural) |
| `notice_combined` | Combined %1$d note into %2$d / Combined %1$d notes into %2$d (plural) |
| `notice_split` | Split %1$d note into %2$d / Split %1$d notes into %2$d (plural) |
| `notice_rearranged` | Rearranged %1$d note into %2$d / Rearranged %1$d notes into %2$d (plural) |
| `notice_renewed` | Renewed %1$d note / Renewed %1$d notes (plural) |
| `home_key_changed_body` | Your wallet stopped working with it, so nothing is sent there. Your notes there are unchanged. Open the details to see the new key and decide whether to use it. |
| `notes_title` | Notes |
| `notes_settings` | Note settings |
| `notes_count` | %1$d note / %1$d notes (plural) |
| `notes_sort` | Sort notes |
| `notes_select_hint` | Tap notes to select them. |
| `notes_share_hint` | Share as QR hands over the whole note. To give part of it, split it first. |
| `notes_renew_hint` | Renew gives the note a fresh key, for example after you showed its QR code to someone by mistake. |
| `notes_checked_ok` | Checked %1$d note: still there. / Checked %1$d notes: all still there. (plural) |
| `notes_checked_gone` | %1$d note is no longer there: it was spent elsewhere, for example with this recovery phrase on another phone. / %1$d notes are no longer there: they were spent elsewhere, for example with this recovery phrase on another phone. (plural) |
| `notes_already_spent` | This note was already spent, so it wasn't shared. It's no longer in your balance. |
| `notes_empty` | No notes yet. Notes appear here once you receive money. |
| `notes_strategy_warning` | "%1$s" is on: it rearranges your notes again on every refresh. |
| `notes_selected` | %1$d note selected · %2$s / %1$d notes selected · %2$s (plural) |
| `notes_combined` | Combined into one note of %1$s. |
| `notes_split_title` | Split note |
| `notes_split_amount_bitcoin` | First note, in ₿ |
| `notes_split_amount_sats` | First note, in sats |
| `notes_split_preview` | You get one note of %1$s and one of %2$s. |
| `notes_split_switches_manual` | "%1$s" would undo this, so this switches to managing notes yourself. |
| `notes_move_hint` | The notes are paid out over Lightning and arrive there as a new note. Both sides charge their fees. |
| `notes_problem_not_found` | These notes changed in the meantime. Select them again. |
| `notes_problem_too_few` | Select at least two notes to combine. |
| `notes_problem_invalid_amount` | This note is too small to split, or the amount doesn't fit. |
| `notes_problem_same_mint` | The notes are already there. |
| `notes_problem_mint_invalid` | That address can't hold notes. |
| `onboarding_tagline` | Bearer notes on Lightning. Ready in one tap. |
| `receive_hint` | Anyone can pay this address from any Lightning wallet. Payments arrive as notes in this wallet. |
| `receive_claim` | Claim a note instead |
| `send_create_note` | Create a note |
| `note_create_title` | Send as a note |
| `note_create_body` | Make a note worth an amount and hand it over as a QR code or link. Whoever gets it can claim the money in a compatible wallet. |
| `note_available` | You can make a note of up to %1$s. |
| `note_create` | Create note |
| `note_create_pending` | The note couldn't be confirmed yet, so nothing was handed out. Try again in a moment. |
| `note_title` | Note |
| `note_qr_description` | QR code of the note |
| `note_uncertified` | The amount of this note isn't certified. |
| `note_share_title` | Share note |
| `note_reclaim_title` | Take this note back? |
| `note_already_redeemed` | Too late: someone already redeemed this note. |
| `note_not_found` | This note isn't in this account. |
| `mint_key_changed` | %1$s now signs with a different key than when you first connected. Until you accept the new key, your wallet doesn't send or receive there. Your notes there are unchanged. |
| `settings_notes` | Note settings |
| `settings_note_list` | Notes |
| `settings_note_list_hint` | See your notes one by one, share, split or combine them |
| `settings_account_contents` | Recovery phrase, addresses, notes |
| `delete_what` | This removes this account from this phone: its recovery phrase, its notes and its settings. Other accounts are not affected. |
| `settings_note_strategy` | Note strategy |
| `settings_strategy_manual_hint` | Keep notes as they arrive. |
| `settings_strategy_single` | Keep only one note |
| `settings_auto_renew` | Auto-renew incoming notes |
| `settings_auto_renew_hint` | Moves each received note to a fresh key of this wallet. |
| `mint_invalid` | Answers, but can't hold notes |
| `mint_key_accept_body` | Only do this if you know why %1$s changed its key, for example because its operator announced it. If someone else is answering in its place, your notes there and anything you send can be lost. |
| `mint_balance` | %1$s in %2$d note / %1$s in %2$d notes (plural) |
| `mint_watch_only_hint` | Lets another app see this account's notes here and receive to it, but never spend. |
| `notes_collect_hint` | Notes you get elsewhere are moved here automatically, when the fees stay within your limit. Then the note strategy above applies to them too. |
| `settings_notices_hint` | A short message when the wallet combined, split, renewed or moved your notes by itself. |
| `notes_collect_pending` | Moving the notes from %1$s; they arrive shortly. |
| `mints_add_invalid` | That address can't hold notes. |
| `mints_remove_body` | The app forgets it, its signing key and whether you trust it. Notes and history stay. If you add it again later, it's like the first time: the key it shows then is the one the app remembers. |

#### Using your own words

To use another word ("custodian", "provider", "voucher", …), override exactly that term's strings in `brand/res/values/strings.xml`, and once per language in `brand/res/values-<lang>/strings.xml`, as whole translated sentences. Word substitution is deliberately not offered: articles, gender and case change with the word in many languages. Keep the `%1$s` placeholders. `dev/check-brand.sh` fails on an override whose string no longer exists (a key renamed in a White Wallet release), so a brand notices before its word quietly falls back to "mint" or "note". New strings that need either word go into the block, so this list stays complete.

## 2. `brand/res/`

| File | What to change |
|---|---|
| `values/brand_colors.xml` | `brand_primary`, `brand_on_primary`, `brand_secondary`, `brand_tertiary`, `brand_launcher_background`, and any other colour role (see "Design") |
| `values-night/brand_colors.xml` | Dark-theme variants |
| `font/`, `values/*.xml` | Fonts, text sizes, corners and spacing (see "Design"): optional |
| `drawable/ic_launcher_foreground.xml` | Launcher icon foreground (adaptive icon, 108dp canvas, keep the mark inside the central 66dp) |
| `drawable/ic_brand_logo.xml` | Logo on the onboarding and app lock screens |
| `drawable/ic_activity_*.xml` | Optional: the History icons (`received`, `claimed`, `sent`, `sent_note`, `moved`, `moved_mint`, `split`, `combined`, `rotated`, and `automatic`, the badge on entries the wallet made by itself). To change how they are framed, see `ActivityIcon` under "Design" |
| `drawable/ic_*.xml` | Optional: the marks of the settings pages (`account`, `mint`, `lock`, `amounts`, `developer`, `info`, `key`, `address`, `tune`, `notes`, `delete`, `warning`, and `page` for a brand page without its own `BrandScreen.icon`) and of notes that are still being paid, unclear or uncertified (`pending`, `unknown`, `uncertified`). To change how they are drawn, see `ListIcon` under "Design" |
| `mipmap-anydpi/ic_launcher.xml` | Usually unchanged; combines background color and foreground |

You can also add `values/strings.xml` here to override any user-facing string from the feature modules, using the same names (for example `onboarding_tagline`). App resources take precedence over library resources. The app ships in English and German; for each language you support, put its brand-term strings in `values-<lang>/strings.xml`.

| File | What to change |
|---|---|
| `zapstore.yaml` | Your Zapstore listing (see §4). The repository root links to it |

## 3. Build and check

```sh
./gradlew :app:assembleRelease
dev/check-brand.sh            # fails if your name, application ID, default mint or colors appear outside brand/
```

Install it next to the original: a different `app.id` installs as a separate app with your name, icon and colors. To build another brand directory without touching `brand/`, pass `-PbrandDir=path`, e.g. `./gradlew :app:assembleDebug -PbrandDir=brand-examples/acme` (and `dev/check-brand.sh brand-examples/acme`). In Android Studio, the shared run configuration "Acme Cash" (`.run/`) installs and opens it (`:app:openDebug -PbrandDir=brand-examples/acme`); copy it for another brand.

## Design

A brand's look comes in levels; each is optional and each builds on the one before. Without any of them the app
looks as it does in this repository. White Wallet only names **roles** (what a colour, a font or a place is for); a
brand chooses every value, under names of its own.

| Level | Where | What |
|---|---|---|
| 1. Tokens | `res/` | colour roles, fonts, text sizes, corners, spacing |
| 2. Theme | `code/`: `BrandDesign.Theme` | change any token, or add CompositionLocals of the brand's own |
| 3. Components | `code/`: `BrandDesign.components` | how buttons, fields, rows, cards, dialogs, sheets, short messages (`Snackbar`), toggles, chips, progress, the tab bar, the top bar, amounts, QR codes and the cover over a secret one (`QrCodeCover`), the icons of History entries (`ActivityIcon`: type, direction, automatic), the marks of list rows (`ListIcon`: settings pages, a note's status), accounts' marks (`AccountMark`: the first letter of the name on a colour by the account's place), notes (`NoteFace`, `NoteGroupHeader`) and the release stage's badge (`ReleaseBadge`) look |
| 4. Decorations | `code/`: `BrandDesign.Decoration` | the brand's own content at named places (a logo, a mascot, a wordmark), knowing the app's name and release stage |
| 5. Status and events | `code/`: `WalletStatus`, `WalletEvent` | what a screen is about and what just happened, to show or react to |
| 6. Screens | `code/`: `BrandScreen` | pages of the brand's own, reached from Settings, the wallet's menu or right after setup |

The wallet keeps its flows, texts and accessibility: a design decides how things look, never what happens. Sending
still asks for confirmation, a shared note is still called a bearer note, the recovery phrase is still verified.

### Level 1: tokens

Every token has a default in `core/designsystem/src/main/res/values/brand_defaults.xml`, which lists them all. A brand
overrides any of them by name in `res/values/` and, separately, in `res/values-night/`. Name your colours however you
like and point the roles at them:

```xml
<color name="my_paper">#FFF6D6</color>
<color name="brand_surface">@color/my_paper</color>
<color name="brand_background">@color/my_paper</color>
```

| Tokens | Names |
|---|---|
| Colour roles (Material 3) | `brand_primary`, `brand_on_primary`, `brand_primary_container`, `brand_on_primary_container`, `brand_inverse_primary`, `brand_secondary` …, `brand_tertiary` …, `brand_background`, `brand_on_background`, `brand_surface`, `brand_on_surface`, `brand_surface_variant`, `brand_on_surface_variant`, `brand_surface_container{_lowest,_low,,_high,_highest}`, `brand_surface_dim`, `brand_surface_bright`, `brand_surface_tint`, `brand_inverse_surface`, `brand_inverse_on_surface`, `brand_error` …, `brand_outline`, `brand_outline_variant`, `brand_scrim` |
| Wallet roles | `brand_incoming`, `brand_outgoing` (history amounts), `brand_success`, `brand_warning`, `brand_warning_container`, `brand_on_warning_container` (notices), `brand_amount` (the balance) |
| Fonts | `font/brand_font_display`, `_headline`, `_title`, `_body`, `_label`: a font file or a font-family XML (several weights). Headline falls back to display; title and label to body; any unset group to the system font |
| Text styles | `brand_text_<style>_size`, `_line_height` (sp) and `brand_text_<style>_weight` (integer), for Material's 15 styles (`display_large` … `label_small`); 0 keeps Material's |
| Shapes | `brand_corner_extra_small`, `_small`, `_medium`, `_large`, `_extra_large`; -1dp keeps Material's |
| Spacing | `brand_spacing_screen` (edge padding, 24dp), `brand_spacing_gap` (between blocks, 16dp) |

A role left alone keeps Material's colour (`@color/brand_unset`); wallet roles fall back to a Material role
(incoming and success to primary, outgoing and amount to on-surface, warning to error). Fonts must be bundled (no
downloadable fonts: they need Google Play Services); keep their licence with them.

### Levels 2 to 6: code

A brand's `code/` module (see "Custom code") sees `core/brand-ui` next to `core/brand-api`, and registers what it
provides with Hilt:

```kotlin
@Module @InstallIn(SingletonComponent::class)
object MyModule {
    @Provides fun design(): BrandDesign = MyDesign                         // levels 2–4, at most one
    @Provides @IntoSet fun haptics(): WalletEventListener = MyHaptics()   // level 5, any number
    @Provides @IntoSet fun welcome(): BrandScreen = Welcome()             // level 6, any number
}
```

`BrandDesign` has a default for every member, so a brand overrides only what it changes and new members in later
releases don't break it; the parameter objects of components (`ButtonParams`, …) only gain options with defaults.

- **Theme:** `Theme(tokens, content)` gets the tokens from the resources (`tokens.copy(typography = …)`) and ends
  with `WalletMaterialTheme(tokens, content)`. Read the wallet roles with `MaterialTheme.walletColors`.
- **Components:** `object MyComponents : BrandComponents { override fun Button(params, modifier) { … } }`. Apply
  the modifier to the root; the texts arrive translated and the semantics are set by the app. `DefaultComponents`
  has the Material versions to fall back on. Screens only use these (`dev/check-design.sh` keeps it that way).
  A `Sheet` with `params.openFully` opens at its full height rather than at half the screen first (a note's
  details, where an action's outcome comes up at the bottom): honour it in a sheet of your own.
- **Notes:** a note is one object the brand designs once. `NoteFace(params, modifier)` draws it as a row of the notes
  list (`NoteFaceSize.Row`) and large (`NoteFaceSize.Large`) on its details, the claim preview and a handed-out note's
  page, where `params.qrCode` is the QR code: place and frame it, never alter or cover it. On a handed-out note's
  page that slot first holds the cover (`QrCodeCover`, as large as the code; by default in the theme's
  `surfaceVariant`, and the brand may draw its own, the app's modifier keeping the tap), until the user taps it and
  again whenever the app leaves the screen (`params.qrCovered`). `params.place` says where the note is: `Wallet`
  (the notes list and a note's details), `Claim` (someone's note before the user claims it), `Shared` (a
  handed-out note: its page, and its row in the notes view, under the handed-out heading) or `Paper` (printed). `params` has the amount
  (formatted and in msat), the `status` (`NoteStatus`: `Ready`, `Paying`, `Unclear`, `Uncertified`, `Waiting`,
  `Redeemed`, `TakenBack`; give a `when` an `else`), its words and mark, the age, the mint (null with
  `mints.visible=false`), whether it is trusted, and `selected` while the user selects (null otherwise).
  `NoteGroupHeader` heads one mint's notes; its `mint` is null with `mints.visible=false`, and then it only separates
  them. The app keeps what a note does: taps, the checkbox state for screen readers, its actions, their
  confirmations and the bearer warning, and on a large face the status, age and mint in words below it. Draw the
  texts you are given with `Text`, so screen readers read them. The defaults draw through your `ListRow`, `Toggle`,
  `ListIcon` and `Amount`. `brand-examples/acme/` draws them as banknotes: a small bill in the list, the whole bill
  on a note's page, with the provider as issuer and the QR code on a white margin of its own.
- **Decorations:** `Decoration(anchor, context, default)`: call `default()` to keep what the app shows there, or
  draw something else. Decorations are decorative; screen readers read the text next to them (for a title anchor,
  the app's name as a heading). `context.reduceMotion` is set when the user turned animations off: change states
  without moving.

  | Anchor | Where | Default |
  |---|---|---|
  | `OnboardingHeader` | above the setup form | `ic_brand_logo` |
  | `OnboardingTitle` | the app's name at setup | the name as a headline |
  | `HomeHeader` | above the balance | nothing |
  | `Loading` | the home screen's loading screen, over the balance, notes and history while the wallet works for more than a moment (status `Working`) | a spinner, filling up while the notes are checked |
  | `HomeTitle` | the wallet tab's top bar | the app's name |
  | `ReceiveHeader` | above the address or invoice | nothing |
  | `ReceiveSuccess` | the screen after a payment arrived (status `PaymentReceived`) | a check mark that draws itself |
  | `SendHeader` | above a payment's review, its progress and a result that isn't a success | nothing |
  | `SendSuccess` | the screen after a payment went out (status `Sent`) | a check mark that draws itself |
  | `ClaimResult` | above a claimed note's result | nothing |
  | `NoteShared` | above a handed-out note's amount (status `WaitingForRecipient`, `Sent` once redeemed, `Idle` once taken back) | nothing |
  | `NotesHeader` | above the notes view's total (status `Empty` without notes, `WaitingForRecipient` while handed-out notes wait, else `Idle`) | nothing |
  | `NotesEmpty` | above the text of an empty notes view | nothing |
  | `AppLock` | the app lock screen | `ic_brand_logo` |
  | `EmptyHistory` | above the text of an empty history | nothing |

- **Status and events:** `context.status` (also `LocalWalletStatus`) is what the screen is about: `None`, `Idle`
  (money, nothing happening), `Empty`, `AwaitingPayment`, `WaitingForRecipient` (a handed-out note nobody has
  redeemed yet), `PaymentReceived`, `Sending`, `Sent`, `Working` (with a `WalletTask`: `Updating`, asking the mints
  what changed; `CheckingNotes`, with how many are checked of how many; `Maintaining`, with its `MaintenanceStep`,
  `Combining`, `Splitting`, `Renewing` or `MovingHome`, and the note strategy's id when the step is the strategy's),
  `Problem` (`NotEnoughFunds`, `Offline`, `Other`). `WalletEvent`s (`PaymentReceived`, `PaymentSent`, `Problem`,
  `BackupDone`, `AccountCreated`, `NoteRedeemed` and `NoteTakenBack` for a handed-out note, whose `PaymentSent` came
  when it was handed out, and `NoteMaintenance` when a note strategy, auto-renew or collecting at the home mint looked
  after the notes by itself, once per run however many notes, with what it did and what that did to the balance) say what just happened anywhere in the app: collect `LocalWalletEvents` in a
  composable, or register a `WalletEventListener` (called on the main thread). Both carry amounts and kinds only.
  Both may gain cases: give every `when` an `else`.
- **Screens:** a `BrandScreen` has an `id` (lowercase letters, digits, `-`), a `title` string resource, an `entry`
  (`Settings`: a row above About; `HomeMenu`: the wallet's top bar menu; `AfterOnboarding`: once, between setup and
  the wallet, at most one) and its `Content(onBack)`, top bar included. It sees no wallet data.

`brand-examples/acme` uses every level: its own palette mapped to roles, squarer corners, a primary button of its
own, a status light above the balance and an "About Acme" page in Settings.

## Custom code

A brand can ship its own code without forking: a `code/` Gradle module in its brand directory is built into the app (settings.gradle.kts includes it as `:brand-code`). It sees only `core/brand-api`, the small set of interfaces a brand may implement, never the wallet itself, so the wallet's safety rules (spend secrets never stored in logs, no melt retried, every step journaled) hold for every brand, and updating to a new White Wallet release doesn't break brand code that sticks to the API.

Today's extension point is the **note strategy** (`whitewallet.brand.NoteStrategy`): how an account keeps its notes on a mint. A strategy sees one mint's notes and its fee, and proposes steps (combine, split, rotate); the wallet checks and runs them and asks again, until the strategy is satisfied. Money never leaves the account through a strategy. `brand-examples/acme/code/` is a complete example ("Combine small notes"):

```
brand-examples/acme/code/
  build.gradle.kts                    plugins { id("whitewallet.brand-code") }, android { namespace = "…" }
  src/main/kotlin/…/CombineSmallNotes.kt the strategy: id, title, description, workingText, plan()
  src/main/kotlin/…/AcmeModule.kt     @Provides @IntoSet fun combineSmallNotes(): NoteStrategy = CombineSmallNotes()
  src/main/res/values*/strings.xml    its name and description, in every language the app ships
```

A strategy may name its own steps for the home screen's loading screen (`workingText`, a string resource: "Sorting your banknotes…"); without, the screen says what each step does ("Combining your notes…").

Then offer it in `brand.properties`: `notes.strategies=combine-small,single,manual` and `notes.strategy.default=combine-small`. A strategy's `id` is stored per account: never change it once released. A misconfigured brand (an unknown id, a default that isn't offered, two strategies with one id) fails on first start with a message naming the problem.

Anything beyond the API (a different flow, a new place to decorate) needs a new extension point in `core/brand-api` or `core/brand-ui`: open an issue upstream rather than forking.

## 4. Sign and publish

1. Create your own release key: `keytool -genkeypair -v -keystore release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000`. Keep it out of git; `*.jks` and `keystore.properties` are ignored.
2. Tell the build where it is, in either way:
   - `keystore.properties` in the project root with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`;
   - or the environment variables `WW_KEYSTORE`, `WW_KEYSTORE_PASSWORD`, `WW_KEY_ALIAS`, `WW_KEY_PASSWORD` (for CI, from repository secrets).

   `./gradlew :app:assembleRelease` then writes a signed `app/build/outputs/apk/release/app-release.apk`. Without a key it builds `app-release-unsigned.apk`. The build leaves out dependency metadata and VCS info, so the same commit builds the same APK, which lets others verify a release.
3. Publish on [Zapstore](https://zapstore.dev) under your own Nostr identity with [zsp](https://github.com/zapstore/zsp): fill in `brand/zapstore.yaml` (`repository`, `name`, `summary`, `description`, `tags`, `license`; `pubkey` is added by `zsp publish --wizard`), then `SIGN_WITH=<nsec, bunker:// URL or browser> zsp publish`. Prefer a NIP-46 bunker over a raw `nsec` in the environment.
