# Everyday development tasks. `just` lists them; see docs/DEVELOPMENT.md for the details behind each.
#
# Pick a device with ANDROID_SERIAL=<id> (see `just devices`) when more than one is connected, and another brand with
# BRAND=<dir> or as an argument (e.g. `just install brand-examples/acme`).

set shell := ["bash", "-euo", "pipefail", "-c"]

adb := env("ADB", `command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb"`)
brand := env("BRAND", "brand")

# List the tasks
default:
    @just --list --unsorted

# Build the debug APK (app/build/outputs/apk/debug/app-debug.apk)
[group('build')]
build brand=brand:
    ./gradlew :app:assembleDebug -PbrandDir={{ brand }}

# Build the release APK (signed if a release key is set up, see docs/WHITE_LABEL.md §4)
[group('build')]
release brand=brand:
    ./gradlew :app:assembleRelease -PbrandDir={{ brand }}

# Install the debug build on the connected phone or emulator (keeps the wallet's data)
[group('build')]
install brand=brand:
    ./gradlew :app:installDebug -PbrandDir={{ brand }}

# Install the debug build and open it
[group('build')]
run brand=brand: (install brand)
    {{ adb }} shell monkey -p "$(just package {{ brand }})" -c android.intent.category.LAUNCHER 1 2>&1 | grep -q "Events injected: 1" || { echo "Installed, but couldn't open the app." >&2; exit 1; }

# Build the latest debug build, install it on the connected phone (not an emulator) and open it
[group('build')]
phone brand=brand:
    #!/usr/bin/env bash
    set -euo pipefail
    mapfile -t phones < <({{ adb }} devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1 }')
    case ${#phones[@]} in
        0) echo "No phone connected. Pair one with 'just pair', or reconnect with 'just connect <ip:port>'." >&2; exit 1 ;;
        1) echo "Installing on ${phones[0]}" ;;
        *) echo "Several phones connected (${phones[*]}); pick one: ANDROID_SERIAL=<id> just run {{ brand }}" >&2; exit 1 ;;
    esac
    ANDROID_SERIAL="${phones[0]}" just run {{ brand }}

# Follow the debug build's log
[group('build')]
logcat brand=brand:
    #!/usr/bin/env bash
    set -euo pipefail
    pid="$({{ adb }} shell pidof "$(just package {{ brand }})" || true)"
    [[ -n "$pid" ]] || { echo "The app isn't running; start it with 'just run {{ brand }}'." >&2; exit 1; }
    exec {{ adb }} logcat --pid="$pid"

# The debug build's package name
[group('build')]
package brand=brand:
    @echo "$(grep -E '^app.id=' {{ brand }}/brand.properties | cut -d= -f2- | tr -d ' ').debug"

# List connected phones and emulators
[group('devices')]
devices:
    {{ adb }} devices -l

# Pair a phone for wireless debugging (phone: Developer options > Wireless debugging > Pair device with pairing code)
[group('devices')]
pair address="" code="":
    #!/usr/bin/env bash
    set -euo pipefail
    address="{{ address }}"; code="{{ code }}"
    [[ -n "$address" ]] || read -rp "Pairing address shown on the phone (ip:port): " address
    [[ -n "$code" ]] || read -rp "Pairing code: " code
    {{ adb }} pair "$address" "$code"
    # Connecting uses another port than pairing: the one under "IP address & Port" on the Wireless debugging screen.
    read -rp "Connect address shown under 'IP address & Port' (ip:port, empty to skip): " connect
    if [[ -n "$connect" ]]; then
        {{ adb }} connect "$connect"
        echo "Connected. If more than one device is listed in 'just devices', use ANDROID_SERIAL=$connect."
    fi

# Connect to an already paired phone over Wi-Fi (the address under "IP address & Port")
[group('devices')]
connect address:
    {{ adb }} connect {{ address }}

# Unit, protocol, wallet, screen and mock-mint tests (the conformance suite is prepared in .conformance/ first)
[group('checks')]
test *gradle_args:
    dev/conformance.sh
    ./gradlew test {{ gradle_args }}

# The tests with the regtest ones, on the local regtest stack (started if it isn't running; stop it with `just regtest-down`)
[group('checks')]
test-regtest *gradle_args:
    dev/regtest/regtest.sh up >/dev/null
    LNURLCASH_REGTEST=1 just test {{ gradle_args }}

# Line and branch coverage of the protocol and wallet modules, regtest tests included (<module>/build/reports/jacoco/test/html)
[group('checks')]
coverage *gradle_args:
    dev/regtest/regtest.sh up >/dev/null
    LNURLCASH_REGTEST=1 just test jacocoTestReport {{ gradle_args }}
    @echo "core/wallet: file://{{ justfile_directory() }}/core/wallet/build/reports/jacoco/test/html/index.html"

# The tests only a device can run (Keystore, SQLCipher, the native secp256k1), on the connected phone or emulator (keeps the wallet's data)
[group('checks')]
test-device brand=brand:
    ./gradlew :core:data:connectedDebugAndroidTest
    ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PbrandDir={{ brand }}
    {{ adb }} install -r app/build/outputs/apk/debug/app-debug.apk
    {{ adb }} install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    # am instrument exits 0 whatever the tests did: its last lines say "OK (n tests)" only when all passed.
    {{ adb }} shell am instrument -w "$(just package {{ brand }}).test/androidx.test.runner.AndroidJUnitRunner" | tee /dev/stderr | grep -q '^OK ('

# Formats every Kotlin source and build script (ktfmt, kotlinlang style, 140 wide)
[group('checks')]
format:
    ./gradlew spotlessApply

# Android lint (fails on new issues)
[group('checks')]
lint:
    ./gradlew :app:lintDebug

# detekt: the bug-finding rules of detekt.yml, with types, on the code and its tests (fails on any finding)
[group('checks')]
detekt:
    ./gradlew detektMain detektTest detektTestFixtures

# Brand values outside the brand directory
[group('checks')]
check-brand brand=brand:
    dev/check-brand.sh {{ brand }}

# Material components used directly in screens instead of the designsystem's
[group('checks')]
check-design:
    dev/check-design.sh

# Screen states that start with a value the screen would draw before the real one
[group('checks')]
check-flash:
    dev/check-flash.sh

# Note lookups at a mint whose signing key wasn't checked first
[group('checks')]
check-lookups:
    dev/check-lookups.sh

# Resource names (strings, plurals, colours) defined in more than one module
[group('checks')]
check-resources:
    dev/check-resources.sh

# Every preview in every configuration, drawn in a brand's design, and what changed since last time (see the index.html it prints)
[group('checks')]
screenshots brand=brand:
    ./gradlew :screenshots:renderScreenshots -Pscreenshots=full -PbrandDir={{ brand }}
    dev/screenshots.py screenshots/build/screenshots/full/"$(basename {{ brand }})" --render screenshots/build/screenshots/render

# Fails only if a preview can't be drawn or a text in it is hard to read. CI runs it on every push; here, `just verify` runs
# it before a merge (docs/DEVELOPMENT.md, "Screenshots").
# Screenshots and the contrast check (slow): a small set for every sample brand, or `brand` alone
[group('checks')]
test-screenshots brand="":
    for b in {{ if brand == "" { "brand brand-examples/*/" } else { brand } }}; do b="${b%/}"; ./gradlew -q :screenshots:renderScreenshots -Pscreenshots=check -PbrandDir="$b"; dev/screenshots.py screenshots/build/screenshots/check/"$(basename "$b")" --render screenshots/build/screenshots/render; done

# Lint runs on its own: beside a release build, its Kotlin analysis of the app's tests sometimes crashes (a lint bug).
# Not the slow parts, which CI runs too: `just verify` adds the release builds (R8 takes minutes), the screenshots and the
# regtest tests; `just test-device` is for a release.
# What CI checks, but the slow parts: formatting, tests, debug build, lint, detekt, brand, design, flash, lookup and resource checks, scaffolder tests
[group('checks')]
check: test
    ./gradlew spotlessCheck
    ./gradlew :app:assembleDebug
    ./gradlew :app:lintDebug
    just detekt
    for b in brand brand-examples/*/; do dev/check-brand.sh "${b%/}"; done
    dev/check-design.sh
    dev/check-flash.sh
    dev/check-lookups.sh
    dev/check-resources.sh
    python3 -m unittest discover -s dev -p 'test_*.py'

# Docker and the regtest stack are checked first. A stack that is down is said so and left down until the regtest tests,
# which start it: while it is up, `just check` would pass a regtest test that doesn't skip without it. The regtest step
# runs the Regtest* classes alone, as CI does (`just check` ran the others), and fails if any of them was skipped.
# Before merging to main or a release: `just check`, the release builds of `brand` and acme, the screenshots and contrast check, the regtest tests
[group('checks')]
verify:
    #!/usr/bin/env bash
    set -euo pipefail
    docker info >/dev/null 2>&1 || { echo "Docker isn't running, and the regtest tests need it." >&2; exit 1; }
    if [[ -z "$(docker compose -p whitewallet-regtest ps -q mint)" ]]; then
        echo "The regtest stack is down: the regtest tests at the end start it (stop it again with 'just regtest-down')." >&2
    else
        echo "The regtest stack is up: a regtest test that doesn't skip without it passes 'just check' here, but not in CI." >&2
    fi
    just check
    ./gradlew :app:assembleRelease
    ./gradlew -q :app:assembleRelease -PbrandDir=brand-examples/acme
    just test-screenshots
    dev/regtest/regtest.sh up >/dev/null
    LNURLCASH_REGTEST=1 ./gradlew :core:wallet:test --tests '*Regtest*' :core:data:testDebugUnitTest --tests '*Regtest*' \
        :feature:onboarding:testDebugUnitTest --tests '*Regtest*'
    for dir in core/wallet/build/test-results/test core/data/build/test-results/testDebugUnitTest feature/onboarding/build/test-results/testDebugUnitTest; do
        results=("$dir"/TEST-*Regtest*.xml)
        [[ -e "${results[0]}" ]] || { echo "No regtest test results in $dir." >&2; exit 1; }
        if grep -h '<skipped' "${results[@]}"; then echo "Regtest tests were skipped in $dir (above)." >&2; exit 1; fi
    done

# Create a new brand (e.g. just new-brand --name "Acme Cash" --id com.acme.cash --mint mint.acme.com --primary "#0B6E4F")
[group('brands')]
new-brand *args:
    dev/new-brand.sh {{ args }}

# Run a regtest command: up, down, reset, status, connect, mine, logs, … (see dev/regtest/README.md)
[group('regtest')]
regtest *args:
    dev/regtest/regtest.sh {{ args }}

# Stop the regtest stack, keeping its state
[group('regtest')]
regtest-down:
    dev/regtest/regtest.sh down

# Pay notes (1,000 sats each) into account `account` of the test phrase that developer mode offers at restore, on the
# first mint of the regtest stack; restore it in the app to see them found. Running it again adds more.
[group('regtest')]
regtest-fund-test-wallet notes="3" account="0":
    #!/usr/bin/env bash
    set -euo pipefail
    if [[ -z "$(docker compose -p whitewallet-regtest ps -q user-lnd 2>/dev/null)" ]]; then
        echo "The regtest stack is not up: start it with 'just regtest up'." >&2; exit 1
    fi
    ./gradlew -q :core:wallet:fundTestWallet --args="{{ notes }} {{ account }}"

# Mint a note on the local mint (--mint2 or --mint3 for another) and type it into the focused field on the device, or with --open open it in the app
[group('regtest')]
note sats="1000" *flags:
    dev/regtest/regtest.sh note {{ sats }} --type {{ flags }}

# Pay an address, invoice or LNURL from the local Lightning node
[group('regtest')]
pay target sats="1000":
    dev/regtest/regtest.sh pay {{ target }} {{ sats }}

# Delete build outputs
[group('housekeeping')]
clean:
    ./gradlew clean
