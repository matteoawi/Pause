# Security & privacy

Pause was made for a developer's own phone and shared with the community. These are design choices and limitations, not a promise of perfect security or an independent audit.

## What the app does

Pause uses Android's local VPN interface to check domain lookups against ad-blocking lists. Only requests to its virtual DNS addresses enter the tunnel. Ordinary app traffic stays on your normal connection; no Pause VPN server carries your browsing or games.

Pause does not decrypt HTTPS or install a certificate. It doesn't require root, Accessibility, location or contacts. You approve the VPN in Android; notifications are optional.

## What leaves your phone

- Allowed lookups go to Cloudflare (`1.1.1.1` / `1.0.0.1`, with IPv6 equivalents), over **unencrypted DNS**. Cloudflare and the network can observe them. This isn't an anonymity service.
- StevenBlack and HaGeZi Normal lists download from GitHub over HTTPS. GitHub receives normal connection metadata.
- No analytics or account system. Pause does not upload statistics or custom rules.
- The static website uses GitHub Pages, with no analytics, forms, cookies set by Pause or external font requests. Its download counter reads the public GitHub Releases API without credentials or a visitor identifier; GitHub receives normal connection metadata. A ten-minute tab-local sessionStorage cache stores only the total and check time. GitHub may process ordinary hosting/API logs under its own policies.

## What stays on your phone

Settings, exclusions, counts, app statistics and downloaded lists stay in private app storage, excluded from Android backups. The last 30 blocked domains are held in memory only. Uninstalling clears app data; installing an official update over the existing app preserves it.

## Downloads and updates

Use [the official release](https://github.com/matteoawi/Pause/releases/latest). Keep Android's installation checks and Play Protect enabled. Don't install if Android reports an unexpected signature mismatch.

Official signing-certificate SHA-256:

```
41d35cc49cd5305bc8cd2b7191ec0a80fb291bcbf4e9f9998c3d08e862ce09f7
```

The release contains `Pause.apk.sha256` to check your file. A checksum detects a different file; it isn't an audit or protection against a compromised publisher account. Android's signature continuity is a separate check. Signing keys stay private.

Lists refresh about every 24 hours while protection runs. After failure, a retry can occur after at least 6 hours. Both sources must validate before an atomic replacement; invalid or over-budget lists leave the previous snapshot in place. App updates require a separate APK installation.

## Limits

No universal blocking, guaranteed uptime, anonymity or protection from all malware. Same-service ads, including YouTube and Instagram's built-in ads, may not be separable from content. Other VPNs replace Pause. An app's encrypted DNS, direct IP connections and cached ads can bypass it.

Don't enable Android's “Block connections without VPN”: ordinary traffic intentionally stays outside the DNS-only tunnel. Always-on VPN isn't supported. Battery management or memory cleanup may stop the app; the background guide explains optional settings without changing them automatically.

## Verification and reporting

Local unit tests cover the filtering and network code, alongside documented historical emulator/device checks. They don't prove compatibility with every phone or game. There has been **no independent security audit**.

For ordinary problems, [open an issue](https://github.com/matteoawi/Pause/issues) with your app version, Android version and a short description. Never include credentials, full browsing histories or sensitive URLs. For vulnerabilities, use “Report a vulnerability” under Security if available; otherwise open a minimal issue asking for a private contact, without exploit details or personal data.

Software is supplied under the license without warranty. Specific, reproducible reports and community review are welcome.
