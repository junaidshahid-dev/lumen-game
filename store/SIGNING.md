# Signing — run these yourself

The keystore is the one thing in this project that must never pass through
anyone else's hands, including mine. Lose it and you can never update the app
under the same listing; leak it and someone else can ship as you. So you
generate it, you choose the passwords, and you keep the backup.

## 1. Generate the upload keystore

`keytool` ships with the JDK. You already have one at
`C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot\bin\keytool.exe`.

Run this in PowerShell, from a folder **outside** the repository:

```bash
keytool -genkeypair -v -keystore lumen-upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias lumen
```

It will prompt for a keystore password, then your name and organisation
details, then a key password. Notes:

- Use a long random password from your password manager, and store both it and
  the file there. Google gives you no recovery path.
- `-validity 10000` is about 27 years. Play requires a key valid past 2033.
- Answer the name and organisation prompts however you like; none of it is shown
  to users.

## 2. Back it up

At minimum: your password manager, plus one encrypted copy somewhere that is not
this laptop. Treat it exactly like the recovery codes for an account you cannot
reset.

## 3. Add the CI secrets

The build reads the keystore from environment variables and never from a file in
the repo. Encode the keystore, then add four secrets to the GitHub repository.

```bash
[Convert]::ToBase64String([IO.File]::ReadAllBytes("lumen-upload.jks")) | Set-Clipboard
```

Then in the repository, **Settings → Secrets and variables → Actions → New
repository secret**, add:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the base64 string now on your clipboard |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | `lumen` |
| `KEY_PASSWORD` | the key password you chose |

Or from the command line, which avoids the clipboard round-trip:

```bash
gh secret set KEYSTORE_BASE64 --repo junaidshahid-dev/lumen-game < lumen-upload.b64
```

## 4. Build the bundle

Push to `main`. The `release` job detects `KEYSTORE_BASE64`, decodes it into the
runner's temp directory, builds `bundleRelease`, and uploads
`lumen-release-aab`. That `.aab` is what you upload to the Play Console.

Until those secrets exist the release job skips itself, which is why every run so
far has produced only the debug APK.

## A note on Play App Signing

Play will ask you to enrol in Play App Signing. Do it. The key you made above
becomes your *upload* key: you sign with it, Google verifies it, then re-signs
with a key it holds. If your upload key is ever lost or compromised, Google can
reset it — which is a safety net you do not get otherwise.
