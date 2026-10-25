# White Wallet

A minimal, white-label Android wallet for [LNURLcash](https://github.com/lnurl/luds/pull/301) (LUD-25/26): bitcoin
held as bearer notes on a Lightning mint, with a Lightning Address and a recovery phrase that stays on your phone.
It works on first launch with preselected defaults, and anyone can ship it under their own brand by configuration
alone. Distributed through [Zapstore](https://zapstore.dev), without Google Play Services.

- A Lightning Address that receives while the phone is off, or an invoice for a set amount
- Pay Lightning invoices, addresses and LNURL-pay links; hand out a note as a link or QR code
- Claim notes from links, QR codes and NFC tags
- Several accounts, each with its own recovery phrase; notes on several mints, collected at home automatically
- Encrypted storage, optional app lock, no analytics
- English and German

## Quick start

Requirements: JDK 21 and the Android SDK (details in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)), optionally
[just](https://just.systems).

```sh
just build        # debug APK
just run          # install on a connected device or emulator and open it
just test         # unit, protocol, wallet and screen tests
just check        # everything CI checks
```

## Make your own brand

```sh
just new-brand --name "Acme Cash" --id com.acme.cash --mint mint.acme.com --primary "#0B6E4F" --logo logo.svg
```

A brand is one directory: name, application ID, colors, icons, texts and feature flags. See
[docs/WHITE_LABEL.md](docs/WHITE_LABEL.md). [Zap of Nakamoto](https://github.com/zapofnakamoto/zap-of-nakamoto) is a
brand kept in its own repository, built and published to Zapstore from there.

## Documentation

- [docs/DESIGN.md](docs/DESIGN.md): what the wallet is, its principles and money-safety rules
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): building, testing, the local regtest stack and how each feature works
- [docs/WHITE_LABEL.md](docs/WHITE_LABEL.md): branding, signing and publishing
- [CONTRIBUTING.md](CONTRIBUTING.md): how to contribute
- [SECURITY.md](SECURITY.md): reporting vulnerabilities privately
- [CHANGELOG.md](CHANGELOG.md): what changed in each release

## License

[MIT](LICENSE)
