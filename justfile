# Everyday development tasks. `just` lists them; see docs/DEVELOPMENT.md for the details behind each.
#
# Pick a device with ANDROID_SERIAL=<id> (see `just devices`) when more than one is connected, and another brand with
# BRAND=<dir> or as an argument (e.g. `just install brand-examples/acme`).

set shell := ["bash", "-euo", "pipefail", "-c"]

adb := env("ADB", `command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb"`)
brand := env("BRAND", "brand")
# A prepared lnurlcash-conformance checkout enables the mock-mint tests (docs/DEVELOPMENT.md, "Tests").
conformance := env("LNURLCASH_CONFORMANCE", justfile_directory() / ".." / "lnurlcash-conformance")

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
    @echo "$(grep -E '^applicationId=' {{ brand }}/brand.properties | cut -d= -f2- | tr -d ' ').debug"

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

# Unit, protocol, wallet and screen tests (plus mock-mint tests when the conformance checkout exists)
[group('checks')]
test *gradle_args:
    #!/usr/bin/env bash
    set -euo pipefail
    if [[ -d "{{ conformance }}" ]]; then export LNURLCASH_CONFORMANCE="{{ conformance }}"; else echo "note: no conformance checkout at {{ conformance }}; mock-mint tests are skipped" >&2; fi
    ./gradlew test {{ gradle_args }}

# The tests, including those against the local regtest stack (started if it isn't running; stop it with `just regtest down`)
[group('checks')]
test-regtest *gradle_args:
    dev/regtest/regtest.sh up >/dev/null
    LNURLCASH_REGTEST=1 just test {{ gradle_args }}

# Android lint (fails on new issues)
[group('checks')]
lint:
    ./gradlew :app:lintDebug

# Brand values outside the brand directory
[group('checks')]
check-brand brand=brand:
    dev/check-brand.sh {{ brand }}

# Everything CI checks: tests, both builds, lint, brand checks for every sample brand, scaffolder tests
[group('checks')]
check: test
    ./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
    for b in brand brand-examples/*/; do dev/check-brand.sh "${b%/}"; done
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

# Mint a note on the local mint (add --mint2 for the second one) and type it into the focused field on the device
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
