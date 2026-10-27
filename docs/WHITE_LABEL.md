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

| Key | Effect |
|---|---|
| `appName` | Launcher label and in-app title |
| `applicationId` | Android package ID. Must be unique to your fork |
| `versionCode`, `versionName` | Release version |
| `defaultServiceHost` | LNURLcash mint preselected during onboarding (`host` or `host:port`) |
| `serviceSuggestions` | Comma-separated mints offered in the onboarding dropdown (the default is always first; users can still pick "Custom…") |
| `allowServiceChange` | `false` hides the mint field, locking users to your mint. With `true`, users can also add a Lightning address on another mint (same recovery phrase) |
| `supportUrl` | Exposed as `BuildConfig.SUPPORT_URL` |
| `requireAddress` | Whether every account needs a Lightning Address. `true` (default): setup asks for a name. `false`: setup only creates the account on its mint, which is a single "Get started" button when `allowServiceChange=false`; users receive by invoice (Receive → amount → QR) and can still get an address later in Settings → Account → Lightning addresses. Restoring skips the address step too |
| `maxAccounts` | How many accounts the app holds. `1` hides everything about accounts (no switcher, no "Add account"); at the limit, new accounts and restores are refused with a message. `0` = no limit (default) |
| `maxAddresses` | Lightning Addresses an account may register per mint. Default `1`; `0` = no limit. Developer mode can override it for testing |
| `noteStrategies` | The note strategies users can choose from under Settings → Account → Note settings, in this order: `single` (keep only one note per mint), `manual` (keep notes as they arrive), and any your code module adds (see "Custom code"). One entry hides the choice. Default `single,manual` |
| `defaultNoteStrategy` | The strategy a new account starts with; one of `noteStrategies`. Default `single` |
| `collectAtHomeMint` | "Move everything to the home mint" in Settings → Account → Note settings: notes on other mints (claimed notes, or an old home mint) are moved to the account's home mint automatically, over Lightning, when the move costs at most the fee cap. `hidden` = not offered, `off` = offered and starts off (default), `on` = offered and starts on (users can switch it per account), `always` = every account collects and users have no switch |
| `collectFeeCap` | The default fee cap for those moves, in percent of what is moved (default `2`). Users pick 1, 2, 5 or 10 % |
| `notesView` | Where users see and manage their notes (share one as a QR code, split, combine, move, recheck, renew): `tab` (default) = a **Notes** tab next to Wallet and History, `settings` = a page under Settings → Account, `hidden` = nowhere; the note strategy keeps the notes by itself and users only see their balance and history |
| `historyFilter` | Filters on the History tab: `types` (default) = chips for received, sent, moved and note maintenance; `maintenance` = one chip that shows or hides note maintenance; `none` = no filters, and note maintenance stays hidden |
| `showMints` | `false` hides everything about mints from users: Settings → Mints, the home mint's page, and on Claim which mint a note is on and whether it's trusted (a note whose amount can't be verified is still flagged). Default `true`. Note settings disappear by themselves when there is nothing to choose: one strategy that renews the address's notes itself (`single`) and `collectAtHomeMint` `hidden` or `always` |
| `appearance` | `system` (default) follows the phone's light or dark setting; `light` or `dark` always uses that scheme, for a brand with a single look. See "Design" |
| `developerOptions` | Developer options in Settings (developer mode, request log, derived keys): `shown` (default) = always; `unlock` = hidden until the user taps the version in Settings → About seven times, as on Android itself; `hidden` = never in release builds. A debug build treats `hidden` as `unlock`, and starts with developer mode on, so the options show there anyway |
| `releaseStage` | How finished the app is: `auto` (default) = beta while `versionName` is below 1.0.0, stable from then on; or `alpha`, `beta`, `stable` as set. Alpha and beta show a badge by the version in About and under the name at setup, each with a sentence on what it means (`release_notice_alpha`, `release_notice_beta`; the badge says `release_stage_alpha`, `release_stage_beta`). Stable shows nothing |
| `trustedMints` | Mints shown as trusted from the start (Settings → Mints; a check mark on their notes, a hint when claiming), comma-separated `host:pubkey` with the mint's signing key (66 hex characters). Trust names the key: a mint presenting another key isn't trusted. Users can untrust them. Each account's home mint is trusted by default anyway. Default empty |
| `defaultAmountUnit` | How amounts are shown (until the user picks another unit under Settings → Amounts, if allowed): `bitcoin` (default, [BIP-177](https://github.com/bitcoin/bips/blob/master/bip-0177.mediawiki): the smallest unit as a whole number with the sign, `₿1,000`) or `sats` (`1,000 sats`). The numbers are the same either way |
| `allowUnitChange` | Whether users may switch the unit under Settings → Amounts (default `true`). With `false` the page is gone and every amount shows in `defaultAmountUnit` |

### Naming the custodian, in any language

The app calls the party that holds users' funds a **mint**. Nearly all text is written without that word ("Couldn't connect", "Not confirmed yet"), so translations stay natural. Only a handful of strings name it; they sit in a block marked *Brand term* at the top of each feature module's `res/values/strings.xml`:

| String | Default (en) |
|---|---|
| `onboarding_service` | Mint |
| `onboarding_service_custom_host` | Mint address |
| `onboarding_custody` | Your funds are held by the mint you choose. Keep amounts small. |
| `settings_home_mint` | Home mint |
| `addresses_home_mint` | Home mint: %1$s |
| `claim_home_mint` | On %1$s, your home mint |
| `notes_home_mint` | Home mint |
| `settings_mints` | Mints |

`brand-examples/acme/` is a complete brand that does this ("provider", in English and German), locks users to one mint, shows sats and adds a note strategy of its own (see "Custom code").

To use another word ("custodian", "provider", …), override exactly these in `brand/res/values/strings.xml`, and once per language in `brand/res/values-<lang>/strings.xml`, as whole translated sentences. Word substitution is deliberately not offered: articles, gender and case change with the word in many languages. Keep the `%1$s` placeholders.

## 2. `brand/res/`

| File | What to change |
|---|---|
| `values/brand_colors.xml` | `brand_primary`, `brand_on_primary`, `brand_secondary`, `brand_tertiary`, `brand_launcher_background`, and any other colour role (see "Design") |
| `values-night/brand_colors.xml` | Dark-theme variants |
| `font/`, `values/*.xml` | Fonts, text sizes, corners and spacing (see "Design"): optional |
| `drawable/ic_launcher_foreground.xml` | Launcher icon foreground (adaptive icon, 108dp canvas, keep the mark inside the central 66dp) |
| `drawable/ic_brand_logo.xml` | Logo on the onboarding and app lock screens |
| `drawable/ic_activity_*.xml` | Optional: the History icons (`received`, `claimed`, `sent`, `sent_note`, `moved`, `moved_service`, `split`, `combined`, `rotated`, and `automatic`, the badge on entries the wallet made by itself). To change how they are framed, see `ActivityIcon` under "Design" |
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

Install it next to the original: a different `applicationId` installs as a separate app with your name, icon and colors. To build another brand directory without touching `brand/`, pass `-PbrandDir=path`, e.g. `./gradlew :app:assembleDebug -PbrandDir=brand-examples/acme` (and `dev/check-brand.sh brand-examples/acme`).

## Design

A brand's look comes in levels; each is optional and each builds on the one before. Without any of them the app
looks as it does in this repository. White Wallet only names **roles** (what a colour, a font or a place is for); a
brand chooses every value, under names of its own.

| Level | Where | What |
|---|---|---|
| 1. Tokens | `res/` | colour roles, fonts, text sizes, corners, spacing |
| 2. Theme | `code/`: `BrandDesign.Theme` | change any token, or add CompositionLocals of the brand's own |
| 3. Components | `code/`: `BrandDesign.components` | how buttons, fields, rows, cards, dialogs, sheets, toggles, chips, progress, the tab bar, the top bar, amounts, QR codes, the icons of History entries (`ActivityIcon`: type, direction, automatic) and the release stage's badge (`ReleaseBadge`) look |
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
- **Decorations:** `Decoration(anchor, context, default)`: call `default()` to keep what the app shows there, or
  draw something else. Decorations are decorative; screen readers read the text next to them (for a title anchor,
  the app's name as a heading). `context.reduceMotion` is set when the user turned animations off: change states
  without moving.

  | Anchor | Where | Default |
  |---|---|---|
  | `OnboardingHeader` | above the setup form | `ic_brand_logo` |
  | `OnboardingTitle` | the app's name at setup | the name as a headline |
  | `HomeHeader` | above the balance | nothing |
  | `HomeTitle` | the wallet tab's top bar | the app's name |
  | `ReceiveHeader` | above the address or invoice | nothing |
  | `ReceiveSuccess` | the screen after a payment arrived (status `PaymentReceived`) | a check mark that draws itself |
  | `SendHeader` | above a payment's review, its progress and a result that isn't a success | nothing |
  | `SendSuccess` | the screen after a payment went out (status `Sent`) | a check mark that draws itself |
  | `ClaimResult` | above a claimed note's result | nothing |
  | `AppLock` | the app lock screen | `ic_brand_logo` |
  | `EmptyHistory` | above the text of an empty history | nothing |

- **Status and events:** `context.status` (also `LocalWalletStatus`) is what the screen is about: `None`, `Idle`
  (money, nothing happening), `Empty`, `AwaitingPayment`, `PaymentReceived`, `Sending`, `Sent`, `Problem`
  (`NotEnoughFunds`, `Offline`, `Other`). `WalletEvent`s (`PaymentReceived`, `PaymentSent`, `Problem`,
  `BackupDone`, `AccountCreated`) say what just happened anywhere in the app: collect `LocalWalletEvents` in a
  composable, or register a `WalletEventListener` (called on the main thread). Both carry amounts and kinds only.
  Both may gain cases: give every `when` an `else`.
- **Screens:** a `BrandScreen` has an `id` (lowercase letters, digits, `-`), a `title` string resource, an `entry`
  (`Settings`: a row above About; `HomeMenu`: the wallet's top bar menu; `AfterOnboarding`: once, between setup and
  the wallet, at most one) and its `Content(onBack)`, top bar included. It sees no wallet data.

`brand-examples/acme` uses every level: its own palette mapped to roles, squarer corners, a primary button of its
own, a status light above the balance and an "About Acme" page in Settings.

## Custom code

A brand can ship its own code without forking: a `code/` Gradle module in its brand directory is built into the app (settings.gradle.kts includes it as `:brand-code`). It sees only `core/brand-api`, the small set of interfaces a brand may implement, never the wallet itself, so the wallet's safety rules (spend secrets never stored in logs, no melt retried, every step journaled) hold for every brand, and updating to a new White Wallet release doesn't break brand code that sticks to the API.

Today's extension point is the **note strategy** (`whitewallet.brand.NoteStrategy`): how an account keeps its notes on a mint. A strategy sees one mint's notes and its fee, and proposes steps (combine, split, rotate); the wallet checks and runs them and asks again, until the strategy is satisfied. Money never leaves the account through a strategy. `brand-examples/acme/code/` is a complete example ("Tidy up small notes"):

```
brand-examples/acme/code/
  build.gradle.kts                    plugins { id("whitewallet.brand-code") }, android { namespace = "…" }
  src/main/kotlin/…/TidySmallNotes.kt the strategy: id, title, description, plan()
  src/main/kotlin/…/AcmeModule.kt     @Provides @IntoSet fun tidySmallNotes(): NoteStrategy = TidySmallNotes()
  src/main/res/values*/strings.xml    its name and description, in every language the app ships
```

Then offer it in `brand.properties`: `noteStrategies=tidy-small,single,manual` and `defaultNoteStrategy=tidy-small`. A strategy's `id` is stored per account: never change it once released. A misconfigured brand (an unknown id, a default that isn't offered, two strategies with one id) fails on first start with a message naming the problem.

Anything beyond the API (a different flow, a new place to decorate) needs a new extension point in `core/brand-api` or `core/brand-ui`: open an issue upstream rather than forking.

## 4. Sign and publish

1. Create your own release key: `keytool -genkeypair -v -keystore release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000`. Keep it out of git; `*.jks` and `keystore.properties` are ignored.
2. Tell the build where it is, in either way:
   - `keystore.properties` in the project root with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`;
   - or the environment variables `WW_KEYSTORE`, `WW_KEYSTORE_PASSWORD`, `WW_KEY_ALIAS`, `WW_KEY_PASSWORD` (for CI, from repository secrets).

   `./gradlew :app:assembleRelease` then writes a signed `app/build/outputs/apk/release/app-release.apk`. Without a key it builds `app-release-unsigned.apk`. The build leaves out dependency metadata and VCS info, so the same commit builds the same APK, which lets others verify a release.
3. Publish on [Zapstore](https://zapstore.dev) under your own Nostr identity with [zsp](https://github.com/zapstore/zsp): fill in `brand/zapstore.yaml` (`repository`, `name`, `summary`, `description`, `tags`, `license`; `pubkey` is added by `zsp publish --wizard`), then `SIGN_WITH=<nsec, bunker:// URL or browser> zsp publish`. Prefer a NIP-46 bunker over a raw `nsec` in the environment.
