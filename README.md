# SilentGate VPN

Native Android VPN client (Kotlin, Jetpack Compose, Material 3, AMOLED black + green).
Package: `com.silentgatevpn.app` · Licence: GPL-3.0 · Made by Anas · **v1.1.0**

## What changed in 1.1.0
- **No more silent unmasked "Protected".** The old *Light* layer (direct + TLS fragment) is now the explicit mode **DPI Bypass Only (No IP Change)**. It is never part of Smart Auto and the shield turns orange with a warning.
- **Zero-config community nodes.** Smart Auto pulls nodes from public subscription feeds (editable in *Profiles*), drops insecure ones, pre-flights them in parallel, connects to the best one and fails over automatically.
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
1. **My server**: your own profile (tried first in Smart Auto, trusted).
2. **Smart Node**: community nodes, verified exit.
3. **WARP**: Obfuscated Cloudflare WARP (last resort in Auto; may geolocate near you, the app tells you).
4. **WARP via Node** and **DPI Bypass Only**: manual modes.

## Build (mobile friendly, GitHub web only)
1. Create a new GitHub repository and upload every file (keep the folder structure, including `.github/workflows/build.yml`).
2. **Actions → Build SilentGate VPN → Run workflow**.
3. The first run builds `libbox.aar` from `hiddify/hiddify-sing-box` (branch `v5`) and is slow; it is cached by commit afterwards.
4. Download the `SilentGateVPN-apk` artifact and install it.

If a step fails, send the log of the failing step.

## Infinix Hot 8 Lite notes
- Probing is limited to 6 parallel checks and 14 nodes, the node cache is capped at 360, UI stats update once per second.
- Set the app to *Unrestricted* battery mode and allow Auto-start (Settings has a shortcut), otherwise XOS may kill the VPN.

## Notes
- Debug-signed release APK: uninstall the old build before installing a new CI build (add your own keystore to avoid this).
- Community nodes are third-party servers. Encrypted, but untrusted: avoid sensitive logins over them. Prefer your own server for that.
- "Under 5 seconds" is typical for a recently-good node (fast path, no pre-flight); a cold start with a fresh node list takes longer.
- The Worker script (`worker/worker.js`) is an untested template and may break Cloudflare's terms.
- Kill switch: Android's Always-on VPN + Block connections without VPN.
- Source must stay public (GPL-3.0) when you share the APK.
