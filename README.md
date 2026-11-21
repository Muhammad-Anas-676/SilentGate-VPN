# SilentGate VPN

Native Android VPN client (Kotlin, Jetpack Compose, Material 3, AMOLED black + green).
Package: `com.silentgatevpn.app` · Licence: GPL-3.0 · Made by Anas · **v1.2.0** · minSdk 24 (Android 7.0+)

## What changed in 1.2.0
**Network and core fixes (thumbnail stalls, failed sites)**
- **TLS fragmentation is OFF by default.** It was applied to every proxy handshake; browsers open 20 to 30 parallel connections for thumbnails and the fragmented handshakes made servers slow or reset them. It is only used when you switch it on in *Settings > Advanced > TLS fragmentation* (and by the DPI-only mode).
- **QUIC is blocked by default** (UDP/443 rejected inside the tunnel, so browsers use TCP). Shadowsocks is no longer assumed to relay UDP. *Allow QUIC* and the per-node *Smart* policy remain in Advanced. Old saved settings are migrated once (fragmentation off, QUIC blocked, mode back to Smart Auto).
- **Stricter node checks** before "Connected": a pre-flight burst of parallel handshakes (nodes that reset or throttle concurrent connections are skipped, slow ones are tried last), then 8 simultaneous CDN fetches (6 must load, median under 3.5 s), a content-filter / WAF check (poisoned DNS, 403, 451, resets), and a latency ceiling. Slow nodes sink in the ranking and rest for 30 minutes.
- **In-session watchdog:** every 10 s (3 s after a network switch) 4 parallel fetches; a stall detector (requests leave, nothing comes back) triggers an immediate check; 2 or 3 failures in a row fail over to the next node or layer.
- **WARP is an honest last resort in Smart Auto.** It is only reached after the private server and every node failed, and Home then says the exit may be near your own country instead of claiming unblocking.
- **Private primary server slot** (VLESS Reality, Trojan, Hysteria2, TUIC, SS, WireGuard): tried first in Smart Auto. Add it in *Settings > Advanced > Private server* (stored encrypted, AES-256-GCM with an Android Keystore key), or build it in via the `SG_PRIMARY_SERVER` GitHub secret (or `-PsgPrimaryServer=...`). Anything inside an APK can be extracted, so do not rely on a built-in link staying private.
- **Pre-warm:** the node list is downloaded the moment you accept the welcome screen and at app start.

**Simple 1-tap UI**
- Three tabs: **Home**, **Apps**, **Settings**. Home has only the power button, one location card (*Fastest Location (Auto)* or a safe country) and a speed card.
- Settings shows Kill Switch, Battery Optimization, Legal & Privacy, About and **Advanced Settings**. Advanced holds connection mode, private server, custom profiles and subscriptions, diagnostics and site test, DNS, QUIC and MTU, TLS fragmentation, WARP settings, safe-exit options and the event log.
- One-screen welcome with a single "I am 18+ and agree to the Terms & Privacy Policy" checkbox and links to the full documents.

## Earlier: 1.1.0
- **No more silent unmasked "Protected".** The old *Light* layer (direct + TLS fragment) is now the explicit mode **DPI Bypass Only (No IP Change)**. It is never part of Smart Auto and the shield turns orange with a warning.
- **Zero-config community nodes.** Smart Auto pulls nodes from public subscription feeds (editable in *Settings > Advanced > Custom profiles*), drops insecure ones, pre-flights them in parallel, connects to the best one and fails over automatically.
- **Safe-exit selector.** *Fastest Safe Location (Auto)* skips your home country and age-verification regions (`GB AU FR DE IT ES IE` and the listed US states). Pick a country on Home to force one.
- **Real exit verification** before "Protected": the IP must differ from your ISP IP, the region must be allowed, and 2 of 4 CDNs (image/script hosts) must load.
- **More protocols:** VLESS (Reality + Vision, WS, gRPC, HTTPUpgrade, XHTTP\*), Trojan, VMess, Hysteria2 (+salamander), TUIC, Shadowsocks (+obfs), WireGuard (`wg://`), raw sing-box JSON. Subscriptions: Base64, plain links, sing-box JSON, Clash YAML.
- **WARP + Node** (experimental): WARP chained through a node so Cloudflare geolocates the node's country, not yours.
- **Stability:** `NOT_VPN` network request and VPN-interface guards (no `tun0` loop), `onLost` only for the tracked network, `setUnderlyingNetworks`, faster health checks right after a Wi-Fi/4G switch.
- **DNS:** DoH bootstrap over IP literals with a fallback chain (Cloudflare, Quad9, AdGuard, Google) chosen at connect time; node hostnames are pre-resolved over DoH so a blocked 1.1.1.1 cannot stop start-up.
- **Media / CDN:** Smart QUIC policy (blocks UDP/443 only when the node cannot carry UDP natively), per-protocol and per-carrier MTU (1280 WARP/WG, ~1350 QUIC, up to 1450 TCP).
- **Accurate stats** from the TUN interface counters (falls back to app UID counters if Android blocks sysfs). **DNS leak check** compares the resolver with the selected one. Version label now reads `BuildConfig.VERSION_NAME`.

\* XHTTP needs a core build that supports it; if the core rejects it the node is skipped automatically.

## Layers
0. **Private server**: your primary server slot (tried first in Smart Auto).
1. **My server**: your own profile (trusted).
2. **Smart Node**: community nodes, verified exit.
3. **WARP**: Obfuscated Cloudflare WARP (last resort in Auto; may geolocate near you, the app tells you).
4. **WARP via Node** and **DPI Bypass Only**: manual modes.

## Build (mobile friendly, GitHub web only)
1. Create a new GitHub repository and upload every file (keep the folder structure, including `.github/workflows/build.yml`).
2. **Actions → Build SilentGate VPN → Run workflow**.
3. The first run builds `libbox.aar` from `hiddify/hiddify-sing-box` (branch `v5`) and is slow; it is cached by commit afterwards.
4. Download the `SilentGateVPN-apk` artifact and install it.

If a step fails, send the log of the failing step.

## Android 7 (API 24) notes
- Android 7.0 has an old CA store. On API 24 the node pre-flight stops at the TCP connect (no TLS handshake); sing-box still verifies certificates itself. A few HTTPS test hosts may fail on 7.0 for the same reason, which is why the media test needs 6 of 8, not 8 of 8.
- Everything else (VPN service, notifications, Keystore AES-GCM, `ACTION_VPN_SETTINGS`) is available from API 24 or guarded by an SDK check.

## Infinix Hot 8 Lite notes
- Probing is limited to 6 parallel sockets (and 6 nodes at once) and 14 nodes, the node cache is capped at 360, UI stats update once per second.
- Set the app to *Unrestricted* battery mode and allow Auto-start (Settings has a shortcut), otherwise XOS may kill the VPN.

## Notes
- Debug-signed release APK: uninstall the old build before installing a new CI build (add your own keystore to avoid this).
- Community nodes are third-party servers. Encrypted, but untrusted: avoid sensitive logins over them. Prefer your own server for that.
- "Under 5 seconds" is typical for a recently-good node (fast path, no pre-flight); a cold start with a fresh node list takes longer.
- The Worker script (`worker/worker.js`) is an untested template and may break Cloudflare's terms.
- Kill switch: Android's Always-on VPN + Block connections without VPN.
- Source must stay public (GPL-3.0) when you share the APK.
