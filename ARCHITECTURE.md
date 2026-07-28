# Architecture

How OffBridge moves bytes between an Android phone and a Windows PC, how those bytes are encrypted, and what an attacker on the same air can and can't do.

Two native clients — Android (Kotlin / Jetpack Compose) and Windows (Kotlin / Compose Multiplatform) — implement the **same wire protocol**. They don't share a networking module; each re-implements the frames and crypto natively, and a set of golden-vector unit tests on both platforms keeps them byte-identical.

## Transports

There are two ways the clients reach each other.

**On a shared network (LAN).** A device announces itself with a UDP broadcast on `:50001` (`FILEBRIDGE_DISCOVERY` → `FILEBRIDGE_DISCOVERY_REPLY`); entries expire after ~20 s. Bulk transfer is TCP on `:65433`; pairing is TCP on `:65435`. This is the common case and needs a router.

**Off-grid (no router).** When there's no shared network, the clients build a direct link themselves:

1. **Pairing** — the sender shows a QR (or taps NFC) encoding a one-time session token, its ephemeral ECDH public key, and the Wi-Fi credentials to join.
2. **BLE control channel** — a Bluetooth LE GATT link carries small control frames while the Wi-Fi link is being set up. A `SenderSessionController` and `ReceiverSessionController` sequence the attempt: `TRY_TRANSPORT → TRANSPORT_RESULT`, one tier at a time, so both sides advance to the *same* rung and never split-brain onto different transports.
3. **Transport ladder** — each Wi-Fi-bearing tier is tried in order, filtered by what the device can actually do right now (permissions, API level):

   | Tier | Mechanism |
   |------|-----------|
   | Wi-Fi Direct — credential join | Join the sender's negotiated group by credentials |
   | `WifiNetworkSpecifier` picker | System picker join (no location permission needed on newer APIs) |
   | Wi-Fi Direct — push-button (PBC) | WPS-style push-button group formation |
   | SoftAP join | Join the sender's mobile hotspot |
   | LocalOnlyHotspot host | Sender hosts a LOHS the receiver joins |

   (A Bluetooth L2CAP *data* tier and Wi-Fi Aware exist in the enum but are dormant.)

4. Once a Wi-Fi link is up, bulk transfer is the same TCP flow as LAN.

## Control frames

All frames are newline-delimited UTF-8 text with `|`-separated fields (filenames split on the *last* `:` so a name containing `:` survives). The manifest is `MANIFEST|name:size|name:size|…`.

| Frame | Direction | Purpose |
|-------|-----------|---------|
| `OFFER` / `MANIFEST` | sender → receiver | Announce a transfer + its file list |
| `OFFER_CHALLENGE` / `OFFER_PROOF` | receiver ⇄ sender | AUTH2 admission proof (see below) |
| `OFFER_RECEIVED` | receiver → sender | Offer accepted for display |
| `ACCEPT_OFFER` | receiver → sender | Start streaming (optionally `fgcm` / `fgcm2` / `ack`) |
| `RESUME_REQUEST` | receiver → sender | Resume from `(fileIndex, offset)` after a drop |
| `DECLINE_OFFER` / `CANCEL_TRANSFER` | either | Abort |
| `P2P_HELLO` / `P2P_WELCOME` / `P2P_BUSY` | off-grid handshake | ECDH + token admission over Wi-Fi Direct |

## Cryptography

**Pairing.** ECDH on secp256r1; the shared secret is hashed (`SHA-256`) to a 256-bit AES key. Both screens show a short PIN / SAS derived from the key (v2 uses an independent HKDF label, `clipshare-pair-pin-v2`) so a human confirms there's no man-in-the-middle. Keys are stored in the Android Keystore / `EncryptedSharedPreferences` and, on Windows, wrapped with DPAPI at rest.

**File bytes — FileSeal.** AES-256-GCM in 64 KiB records with HKDF-SHA256 subkeys. The record's position is bound into the GCM AAD, so records can't be truncated, reordered, or spliced without failing the tag. **v1** binds the chunk index; **v2** adds a per-session key and a session/file-bound AAD prefix.

**Clipboard — ClipboardSeal.** AES-256-GCM with the timestamp bound into the payload, a per-peer replay-nonce cache, and a 10-minute freshness window. The legacy CBC path was removed in 3.5.0 — non-sealed payloads are now rejected.

**Control frames.** GCM with strictly monotonic per-direction sequence numbers (`SessionCrypto`), so a control frame can't be replayed or reordered.

**AUTH2 admission.** The LAN trust gate only checks that an incoming IP has a stored pairing key — an IP-spoofer could clear that. AUTH2 closes it: before showing the incoming-file prompt, the receiver sends a nonce and requires `HMAC-SHA256(pairingKey, nonce)` back. No proof, no prompt.

**Anti-downgrade (TOFU).** Capability flags (authenticated clipboard, FileSeal v1/v2, AUTH2) ride the *unauthenticated* UDP discovery, so an on-path attacker could strip them to force a weaker path. `DowngradePolicy` is a single pure decision — `SEAL` / `PLAINTEXT` / `BLOCK` — shared by every capability: once a peer has negotiated the authenticated channel it's **pinned**, and a later strip returns `BLOCK` (fail closed) rather than a silent downgrade. Pins cover GCM1 (clipboard), FGCM1/FGCM2 (files), and AUTH2; they're cleared on unpair so a deliberate re-pair resets the trust state.

## Threat model

The attacker is on the same Wi-Fi network or within radio range.

**They can:**
- Observe encrypted traffic (sizes, timing, endpoints).
- Strip a capability flag from the unauthenticated UDP discovery.
- Spoof a paired peer's IP address on the LAN.

**They can't:**
- Read or forge a sealed frame — they don't hold the pairing key.
- Get past the AUTH2 challenge, so an IP-spoofed offer never reaches the user prompt.
- Force a silent downgrade — a pinned peer that drops its authenticated capability is blocked, not quietly stepped down.
- Reorder, replay, truncate, or splice sealed records — position is bound into the AEAD, control frames carry monotonic sequence numbers, and clipboard payloads carry a bound timestamp + replay cache.

**Honest limits:**
- **Off-grid bulk bytes are plaintext** over the Wi-Fi Direct link. That link is WPA2-protected with credentials delivered only inside the QR, and admission is authenticated by the ECDH + token `P2P_HELLO` handshake — but the bytes themselves aren't double-sealed on that already-private channel. On a shared LAN, file bytes *are* FileSeal-sealed.
- Pins are keyed by IP; a peer whose IP changes (DHCP) re-pins on next authenticated contact.
- TOFU trades a first-contact assumption for zero configuration: the first authenticated session with a peer is trusted, and everything after is measured against it.

## Where to look in the code

| Concern | Android | Windows |
|---------|---------|---------|
| Transport ladder | `app/.../transport/TransportLadder.kt`, `SenderSessionController.kt` | `.../network/QrHostCoordinator.kt` |
| File AEAD | `app/.../transport/FileSeal.kt` | `.../storage/FileSeal.kt` |
| Anti-downgrade | `app/.../transport/DowngradePolicy.kt` | `.../network/DowngradePolicy.kt` |
| Off-grid handshake | `app/.../P2pHelloHandler.kt`, `OffGridHandshake.kt` | `.../network/control/` |
| Pairing / keys | `app/.../PairingDaemon.kt`, `SecurityVault.kt` | `.../network/PairingDaemon.kt`, `.../storage/SecurityVault.kt` |
