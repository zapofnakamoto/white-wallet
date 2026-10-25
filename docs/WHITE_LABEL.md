# White-labeling

A fork rebrands the app by editing **only** `brand/`. No Kotlin changes are needed.

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
| `showMints` | `false` hides everything about mints from users: Settings → Mints, the home mint's page, and on Claim which mint a note is on and whether it's trusted (a note whose amount can't be verified is still flagged). Default `true`. Note settings disappear by themselves when there is nothing to choose: one strategy that renews the address's notes itself (`single`) and `collectAtHomeMint` `hidden` or `always` |
| `developerOptions` | Developer options in Settings (developer mode, request log, derived keys): `shown` (default) = always; `unlock` = hidden until the user taps the version in Settings → About seven times, as on Android itself; `hidden` = never in release builds. A debug build treats `hidden` as `unlock`, and starts with developer mode on, so the options show there anyway |
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
| `values/brand_colors.xml` | `brand_primary`, `brand_on_primary`, `brand_secondary`, `brand_tertiary`, `brand_launcher_background` |
| `values-night/brand_colors.xml` | Dark-theme variants |
| `drawable/ic_launcher_foreground.xml` | Launcher icon foreground (adaptive icon, 108dp canvas, keep the mark inside the central 66dp) |
| `drawable/ic_brand_logo.xml` | Logo on the onboarding screen |
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

Anything beyond the API (another screen, a different flow) needs a new extension point in `core/brand-api`: open an issue upstream rather than forking.

## 4. Sign and publish

1. Create your own release key: `keytool -genkeypair -v -keystore release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000`. Keep it out of git; `*.jks` and `keystore.properties` are ignored.
2. Tell the build where it is, in either way:
   - `keystore.properties` in the project root with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`;
   - or the environment variables `WW_KEYSTORE`, `WW_KEYSTORE_PASSWORD`, `WW_KEY_ALIAS`, `WW_KEY_PASSWORD` (for CI, from repository secrets).

   `./gradlew :app:assembleRelease` then writes a signed `app/build/outputs/apk/release/app-release.apk`. Without a key it builds `app-release-unsigned.apk`. The build leaves out dependency metadata and VCS info, so the same commit builds the same APK, which lets others verify a release.
3. Publish on [Zapstore](https://zapstore.dev) under your own Nostr identity with [zsp](https://github.com/zapstore/zsp): fill in `brand/zapstore.yaml` (`repository`, `name`, `summary`, `description`, `tags`, `license`; `pubkey` is added by `zsp publish --wizard`), then `SIGN_WITH=<nsec, bunker:// URL or browser> zsp publish`. Prefer a NIP-46 bunker over a raw `nsec` in the environment.
