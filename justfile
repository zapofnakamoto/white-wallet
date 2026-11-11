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

# The tests on both regtest stacks, legacy then current (each started if it isn't running; stop them with `just regtest-down`)
[group('checks')]
test-regtest *gradle_args:
    #!/usr/bin/env bash
    # Every regtest test runs on both stacks: the legacy mints (lnurl-mint 0.12.2) and the current ones (0.13.1).
    set -uo pipefail
    failed=()
    for protocol in legacy current; do
        echo "==> regtest tests on the $protocol stack"
        just test-regtest-$protocol {{ gradle_args }} || failed+=("$protocol")
    done
    (( ${#failed[@]} == 0 )) || { echo "regtest tests failed on: ${failed[*]}" >&2; exit 1; }

# The tests on the current regtest stack (lnurl-mint 0.13.1)
[group('checks')]
test-regtest-current *gradle_args:
    REGTEST_PROTOCOL=current dev/regtest/regtest.sh up >/dev/null
    REGTEST_PROTOCOL=current LNURLCASH_REGTEST=1 just test {{ gradle_args }}

# The tests on the legacy regtest stack (lnurl-mint 0.12.2). Temporary: goes with legacy 2f17578, see protocol/SPEC.md
[group('checks')]
test-regtest-legacy *gradle_args:
    REGTEST_PROTOCOL=legacy dev/regtest/regtest.sh up >/dev/null
    REGTEST_PROTOCOL=legacy LNURLCASH_REGTEST=1 just test {{ gradle_args }}

# Line and branch coverage of the protocol and wallet modules, regtest tests included (<module>/build/reports/jacoco/test/html)
[group('checks')]
coverage *gradle_args:
    dev/regtest/regtest.sh up >/dev/null
    LNURLCASH_REGTEST=1 just test jacocoTestReport {{ gradle_args }}
    @echo "core/wallet: file://{{ justfile_directory() }}/core/wallet/build/reports/jacoco/test/html/index.html"

# Formats every Kotlin source and build script (ktfmt, kotlinlang style, 140 wide)
[group('checks')]
format:
    ./gradlew spotlessApply

# Android lint (fails on new issues)
[group('checks')]
lint:
    ./gradlew :app:lintDebug

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

# Everything CI checks: formatting, tests, both builds, lint, brand, design and flash checks, scaffolder tests
[group('checks')]
check: test
    ./gradlew spotlessCheck
    ./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
    for b in brand brand-examples/*/; do dev/check-brand.sh "${b%/}"; done
    dev/check-design.sh
    dev/check-flash.sh
    dev/check-legacy.sh
    python3 -m unittest discover -s dev -p 'test_*.py'
    ./gradlew -q :app:assembleRelease -PbrandDir=brand-examples/acme

# Create a new brand (e.g. just new-brand --name "Acme Cash" --id com.acme.cash --mint mint.acme.com --primary "#0B6E4F")
[group('brands')]
new-brand *args:
    dev/new-brand.sh {{ args }}

# Run a regtest command: up, down, reset, status, connect, mine, logs, … (see dev/regtest/README.md)
[group('regtest')]
regtest *args:
    dev/regtest/regtest.sh {{ args }}

# Stop both regtest stacks (`just regtest down` stops only the current one)
[group('regtest')]
regtest-down:
    REGTEST_PROTOCOL=current dev/regtest/regtest.sh down
    REGTEST_PROTOCOL=legacy dev/regtest/regtest.sh down

# Pay notes (1,000 sats each) into account `account` of the test phrase that developer mode offers at restore, on the
# first mint of the regtest stack that is up; restore it in the app to see them found. Running it again adds more.
[group('regtest')]
regtest-fund-test-wallet notes="3" account="0":
    #!/usr/bin/env bash
    set -euo pipefail
    # Both stacks use the same ports: whichever is up gets the notes.
    if [[ -n "$(docker compose -p whitewallet-regtest-current ps -q user-lnd 2>/dev/null)" ]]; then protocol=current
    elif [[ -n "$(docker compose -p whitewallet-regtest ps -q user-lnd 2>/dev/null)" ]]; then protocol=legacy
    else echo "No regtest stack is up: start one with 'just regtest up'." >&2; exit 1; fi
    REGTEST_PROTOCOL=$protocol ./gradlew -q :core:wallet:fundTestWallet --args="{{ notes }} {{ account }}"

# Mint a note on the local mint (--mint2 to --mint5 for another) and type it into the focused field on the device, or with --open open it in the app
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
