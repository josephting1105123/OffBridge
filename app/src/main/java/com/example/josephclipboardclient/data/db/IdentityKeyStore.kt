package com.EntropicSoftwareLab.ClipboardShare.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/**
 * Long-term per-install identity (V4 §6/§10 Phase 3): a P-256 keypair in the
 * Android Keystore, never exported. Its public key travels in the QR `idk`
 * field and the HELLO_RX frame; `peer_id` = hex SHA-256 of the peer's DER —
 * purely for peer recognition + adaptive transport ordering, NOT a session
 * security input (the per-session ephemeral key is). Future TOFU signing is
 * why PURPOSE_SIGN is declared.
 */
object IdentityKeyStore {

    private const val TAG = "OffGridIdentity"
    private const val ALIAS = "offgrid_identity_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    @Volatile
    private var cachedDer: ByteArray? = null

    /** X.509 DER public key, or null when the Keystore is unavailable (rare OEM breakage). */
    fun publicKeyDer(@Suppress("UNUSED_PARAMETER") context: Context): ByteArray? {
        cachedDer?.let { return it }
        synchronized(this) {
            cachedDer?.let { return it }
            return try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                // Reading an invalidated entry (e.g. lock-screen credential change) can throw.
                // Treat any retrieval failure as "no usable key" and fall through to regenerate.
                val existing = try {
                    ks.getCertificate(ALIAS)?.publicKey?.encoded
                } catch (e: Exception) {
                    Log.w(TAG, "stale identity entry, will regenerate: ${e.message}")
                    null
                }
                val der = existing ?: run {
                    // A stale/invalidated alias can make generate() with the same alias throw,
                    // so drop the old entry first before creating a fresh keypair.
                    runCatching { ks.deleteEntry(ALIAS) }
                    generate()
                }
                // Only cache on success; on failure we return null WITHOUT caching it so a
                // later call (e.g. after the user re-establishes credentials) can retry.
                cachedDer = der
                der
            } catch (e: Exception) {
                Log.w(TAG, "identity key unavailable: ${e.message}")
                null
            }
        }
    }

    private fun generate(): ByteArray {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        kpg.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return kpg.generateKeyPair().public.encoded
    }
}
