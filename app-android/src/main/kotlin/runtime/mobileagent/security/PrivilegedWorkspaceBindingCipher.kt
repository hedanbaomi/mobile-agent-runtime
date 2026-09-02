// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import runtime.mobileagent.domain.Authority

/**
 * The identity which is authenticated alongside a privileged directory
 * locator.  The locator itself is intentionally not a property of this
 * value, and this value's string form omits all identity fields.
 */
data class PrivilegedWorkspaceBindingAad(
    val appInstanceId: String,
    val workspaceId: String,
    val authority: Authority,
    val locatorVersion: Int,
) {
    init {
        require(isSafeIdentity(appInstanceId)) { "App identity is invalid" }
        require(isSafeIdentity(workspaceId)) { "Workspace identity is invalid" }
        require(authority == Authority.SHIZUKU || authority == Authority.WIRED_ADB) {
            "Privileged workspace authority is invalid"
        }
        require(locatorVersion in 1..MAX_LOCATOR_VERSION) { "Locator version is invalid" }
    }

    override fun toString(): String =
        "PrivilegedWorkspaceBindingAad(authority=${authority.name}, locatorVersion=$locatorVersion)"

    companion object {
        private const val MAX_LOCATOR_VERSION = 0x7fff_ffff
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")

        private fun isSafeIdentity(value: String): Boolean =
            value.length in 1..256 && value == value.trim() && SAFE_ID.matches(value)
    }
}

/** Stable, typed reasons for a binding operation to fail. */
enum class PrivilegedWorkspaceBindingErrorCode {
    INVALID_INPUT,
    ENVELOPE_INVALID,
    KEY_UNAVAILABLE,
    KEY_INVALID,
    AAD_MISMATCH,
    AUTHENTICATION_FAILED,
    CRYPTOGRAPHIC_FAILURE,
}

/**
 * Safe error returned by the binding seam.  In particular, it never carries
 * a provider exception message, path, locator, or key material.
 */
data class PrivilegedWorkspaceBindingError(
    val code: PrivilegedWorkspaceBindingErrorCode,
) {
    /** Every persisted-state/crypto failure requires replacement or repair. */
    val unrecoverable: Boolean
        get() = code != PrivilegedWorkspaceBindingErrorCode.INVALID_INPUT

    val safeMessage: String
        get() = when (code) {
            PrivilegedWorkspaceBindingErrorCode.INVALID_INPUT -> "Invalid workspace binding input"
            PrivilegedWorkspaceBindingErrorCode.ENVELOPE_INVALID -> "Workspace binding is invalid"
            PrivilegedWorkspaceBindingErrorCode.KEY_UNAVAILABLE -> "Workspace binding key is unavailable"
            PrivilegedWorkspaceBindingErrorCode.KEY_INVALID -> "Workspace binding key is invalid"
            PrivilegedWorkspaceBindingErrorCode.AAD_MISMATCH -> "Workspace binding identity does not match"
            PrivilegedWorkspaceBindingErrorCode.AUTHENTICATION_FAILED -> "Workspace binding authentication failed"
            PrivilegedWorkspaceBindingErrorCode.CRYPTOGRAPHIC_FAILURE -> "Workspace binding could not be processed"
        }
}

sealed interface PrivilegedWorkspaceBindingSealResult {
    data class Success(val envelope: PrivilegedWorkspaceBindingEnvelope) : PrivilegedWorkspaceBindingSealResult
    data class Failure(val error: PrivilegedWorkspaceBindingError) : PrivilegedWorkspaceBindingSealResult
}

sealed interface PrivilegedWorkspaceBindingOpenResult {
    /** The caller owns this byte array and must clear it after reattachment. */
    data class Success(val locator: ByteArray) : PrivilegedWorkspaceBindingOpenResult
    data class Failure(val error: PrivilegedWorkspaceBindingError) : PrivilegedWorkspaceBindingOpenResult
}

sealed interface PrivilegedWorkspaceBindingEnvelopeDecodeResult {
    data class Success(val envelope: PrivilegedWorkspaceBindingEnvelope) : PrivilegedWorkspaceBindingEnvelopeDecodeResult
    data class Failure(val error: PrivilegedWorkspaceBindingError) : PrivilegedWorkspaceBindingEnvelopeDecodeResult
}

/**
 * Opaque, database-ready AEAD envelope.  It contains only versioned
 * ciphertext, nonce and an AAD digest; it never contains a plaintext locator.
 * The constructor is private so malformed persisted bytes can only enter via
 * [decode], which returns a typed failure.
 */
class PrivilegedWorkspaceBindingEnvelope private constructor(
    val formatVersion: Int,
    private val nonceBytes: ByteArray,
    private val ciphertextBytes: ByteArray,
    private val aadDigestBytes: ByteArray,
    private val encodedBytes: ByteArray,
) {
    /** A defensive copy suitable for passing to a SQLite BLOB column. */
    val nonce: ByteArray get() = nonceBytes.copyOf()
    val ciphertext: ByteArray get() = ciphertextBytes.copyOf()
    val aadDigest: ByteArray get() = aadDigestBytes.copyOf()

    /** Names matching the durable domain row for callers that store columns separately. */
    val locatorNonce: ByteArray get() = nonce
    val encryptedLocator: ByteArray get() = ciphertext

    /** A defensive copy of the complete envelope. */
    fun toByteArray(): ByteArray = encodedBytes.copyOf()

    override fun toString(): String =
        "PrivilegedWorkspaceBindingEnvelope(formatVersion=$formatVersion, " +
            "nonceBytes=${nonceBytes.size}, ciphertextBytes=${ciphertextBytes.size})"

    companion object {
        private const val MAGIC = "MAR-PWB"
        private val MAGIC_BYTES = MAGIC.toByteArray(StandardCharsets.US_ASCII)
        private const val FORMAT_VERSION = 1
        private const val NONCE_BYTES = 12
        private const val AAD_DIGEST_BYTES = 32
        private const val GCM_TAG_BYTES = 16
        private const val MAX_LOCATOR_BYTES = 64 * 1024
        private const val MAX_CIPHERTEXT_BYTES = MAX_LOCATOR_BYTES + GCM_TAG_BYTES
        private val HEADER_BYTES = MAGIC_BYTES.size + 1 + 1 + 1 + 4
        private val MAX_ENVELOPE_BYTES = HEADER_BYTES + NONCE_BYTES + AAD_DIGEST_BYTES + MAX_CIPHERTEXT_BYTES

        internal fun create(
            nonce: ByteArray,
            ciphertext: ByteArray,
            aadDigest: ByteArray,
        ): PrivilegedWorkspaceBindingEnvelope {
            require(nonce.size == NONCE_BYTES)
            require(ciphertext.size in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES)
            require(aadDigest.size == AAD_DIGEST_BYTES)
            val encoded = encode(nonce, ciphertext, aadDigest)
            return PrivilegedWorkspaceBindingEnvelope(
                formatVersion = FORMAT_VERSION,
                nonceBytes = nonce.copyOf(),
                ciphertextBytes = ciphertext.copyOf(),
                aadDigestBytes = aadDigest.copyOf(),
                encodedBytes = encoded,
            )
        }

        /** Decode a SQLite BLOB without allowing malformed bytes to throw across the seam. */
        fun decode(encoded: ByteArray): PrivilegedWorkspaceBindingEnvelopeDecodeResult {
            if (encoded.size !in HEADER_BYTES..MAX_ENVELOPE_BYTES) {
                return decodeFailure()
            }
            val copy = encoded.copyOf()
            var success = false
            return try {
                val buffer = ByteBuffer.wrap(copy)
                val magic = ByteArray(MAGIC_BYTES.size)
                buffer.get(magic)
                if (!magic.contentEquals(MAGIC_BYTES)) return decodeFailure()
                val formatVersion = buffer.get().toInt() and 0xff
                val nonceLength = buffer.get().toInt() and 0xff
                val digestLength = buffer.get().toInt() and 0xff
                val ciphertextLength = buffer.int
                if (
                    formatVersion != FORMAT_VERSION ||
                    nonceLength != NONCE_BYTES ||
                    digestLength != AAD_DIGEST_BYTES ||
                    ciphertextLength !in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES ||
                    buffer.remaining() != nonceLength + digestLength + ciphertextLength
                ) {
                    return decodeFailure()
                }
                val nonce = ByteArray(nonceLength)
                val aadDigest = ByteArray(digestLength)
                val ciphertext = ByteArray(ciphertextLength)
                buffer.get(nonce)
                buffer.get(aadDigest)
                buffer.get(ciphertext)
                val envelope = PrivilegedWorkspaceBindingEnvelope(
                    formatVersion = formatVersion,
                    nonceBytes = nonce,
                    ciphertextBytes = ciphertext,
                    aadDigestBytes = aadDigest,
                    encodedBytes = copy,
                )
                success = true
                PrivilegedWorkspaceBindingEnvelopeDecodeResult.Success(envelope)
            } catch (_: RuntimeException) {
                decodeFailure()
            } finally {
                if (!success) copy.fill(0)
            }
        }

        private fun decodeFailure(): PrivilegedWorkspaceBindingEnvelopeDecodeResult.Failure =
            PrivilegedWorkspaceBindingEnvelopeDecodeResult.Failure(
                PrivilegedWorkspaceBindingError(PrivilegedWorkspaceBindingErrorCode.ENVELOPE_INVALID),
            )

        private fun encode(nonce: ByteArray, ciphertext: ByteArray, aadDigest: ByteArray): ByteArray {
            val output = ByteArrayOutputStream(HEADER_BYTES + nonce.size + aadDigest.size + ciphertext.size)
            val data = DataOutputStream(output)
            data.write(MAGIC_BYTES)
            data.writeByte(FORMAT_VERSION)
            data.writeByte(nonce.size)
            data.writeByte(aadDigest.size)
            data.writeInt(ciphertext.size)
            data.write(nonce)
            data.write(aadDigest)
            data.write(ciphertext)
            return output.toByteArray()
        }
    }
}

/**
 * Android Keystore AES-256-GCM protection for privileged workspace locators.
 *
 * The Keystore key is generated once and retained by Android across app
 * process restarts.  A caller may provide a unique alias in tests; production
 * should use the default alias through [AndroidKeystorePrivilegedWorkspaceBindingCipherFactory].
 */
class PrivilegedWorkspaceBindingCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    // A process-wide alias lock also protects two independently injected
    // cipher instances from racing while creating the same Keystore entry.
    private val monitor = LOCKS.computeIfAbsent(keyAlias) { Any() }

    init {
        require(keyAlias.length in 1..128)
        require(keyAlias.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' })
    }

    /** Encrypt a provider-specific canonical locator for database persistence. */
    fun seal(locator: ByteArray, binding: PrivilegedWorkspaceBindingAad): PrivilegedWorkspaceBindingSealResult {
        if (locator.isEmpty() || locator.size > MAX_LOCATOR_BYTES) {
            return sealFailure(PrivilegedWorkspaceBindingErrorCode.INVALID_INPUT)
        }
        val aad = try {
            canonicalAad(binding)
        } catch (_: RuntimeException) {
            return sealFailure(PrivilegedWorkspaceBindingErrorCode.INVALID_INPUT)
        }
        val plaintext = locator.copyOf()
        var ciphertext = ByteArray(0)
        var nonce = ByteArray(0)
        var digest = ByteArray(0)
        return synchronized(monitor) {
            try {
                when (val key = key(create = true)) {
                    is KeyLookup.Failure -> PrivilegedWorkspaceBindingSealResult.Failure(
                        PrivilegedWorkspaceBindingError(key.code),
                    )
                    is KeyLookup.Success -> {
                        val cipher = Cipher.getInstance(TRANSFORMATION)
                        // Android Keystore supplies a fresh, cryptographically random IV.
                        cipher.init(Cipher.ENCRYPT_MODE, key.value)
                        cipher.updateAAD(aad)
                        ciphertext = cipher.doFinal(plaintext)
                        nonce = cipher.iv.copyOf()
                        digest = sha256(aad)
                        if (nonce.size != NONCE_BYTES || ciphertext.size !in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES) {
                            PrivilegedWorkspaceBindingSealResult.Failure(
                                PrivilegedWorkspaceBindingError(PrivilegedWorkspaceBindingErrorCode.CRYPTOGRAPHIC_FAILURE),
                            )
                        } else {
                            PrivilegedWorkspaceBindingSealResult.Success(
                                PrivilegedWorkspaceBindingEnvelope.create(nonce, ciphertext, digest),
                            )
                        }
                    }
                }
            } catch (_: GeneralSecurityException) {
                sealFailure(PrivilegedWorkspaceBindingErrorCode.CRYPTOGRAPHIC_FAILURE)
            } catch (_: RuntimeException) {
                sealFailure(PrivilegedWorkspaceBindingErrorCode.CRYPTOGRAPHIC_FAILURE)
            } finally {
                plaintext.fill(0)
                aad.fill(0)
                ciphertext.fill(0)
                nonce.fill(0)
                digest.fill(0)
            }
        }
    }

    /** Open an envelope already decoded from a SQLite BLOB. */
    fun open(
        envelope: PrivilegedWorkspaceBindingEnvelope,
        binding: PrivilegedWorkspaceBindingAad,
    ): PrivilegedWorkspaceBindingOpenResult = openInternal(envelope, binding)

    /** Decode and open a SQLite BLOB in one typed operation. */
    fun open(
        encoded: ByteArray,
        binding: PrivilegedWorkspaceBindingAad,
    ): PrivilegedWorkspaceBindingOpenResult = when (val decoded = PrivilegedWorkspaceBindingEnvelope.decode(encoded)) {
        is PrivilegedWorkspaceBindingEnvelopeDecodeResult.Failure ->
            PrivilegedWorkspaceBindingOpenResult.Failure(decoded.error)
        is PrivilegedWorkspaceBindingEnvelopeDecodeResult.Success -> openInternal(decoded.envelope, binding)
    }

    /**
     * Open the ciphertext and nonce when the database stores them in separate
     * columns.  This form does not require persisting the optional AAD digest;
     * GCM authentication still binds the supplied AAD and reports a wrong
     * binding as the same typed unrecoverable authentication failure used for
     * tampered ciphertext.
     */
    fun open(
        encryptedLocator: ByteArray,
        locatorNonce: ByteArray,
        binding: PrivilegedWorkspaceBindingAad,
    ): PrivilegedWorkspaceBindingOpenResult = openParts(encryptedLocator, locatorNonce, binding)

    private fun openInternal(
        envelope: PrivilegedWorkspaceBindingEnvelope,
        binding: PrivilegedWorkspaceBindingAad,
    ): PrivilegedWorkspaceBindingOpenResult {
        val aad = try {
            canonicalAad(binding)
        } catch (_: RuntimeException) {
            return openFailure(PrivilegedWorkspaceBindingErrorCode.INVALID_INPUT)
        }
        var expectedDigest = ByteArray(0)
        var storedDigest = ByteArray(0)
        var nonce = ByteArray(0)
        var ciphertext = ByteArray(0)
        var recovered = ByteArray(0)
        var keepRecovered = false
        return synchronized(monitor) {
            try {
                expectedDigest = sha256(aad)
                storedDigest = envelope.aadDigest
                if (!MessageDigest.isEqual(expectedDigest, storedDigest)) {
                    return@synchronized openFailure(PrivilegedWorkspaceBindingErrorCode.AAD_MISMATCH)
                }

                when (val key = key(create = false)) {
                    is KeyLookup.Failure -> PrivilegedWorkspaceBindingOpenResult.Failure(
                        PrivilegedWorkspaceBindingError(key.code),
                    )
                    is KeyLookup.Success -> {
                        nonce = envelope.nonce
                        ciphertext = envelope.ciphertext
                        val cipher = Cipher.getInstance(TRANSFORMATION)
                        cipher.init(
                            Cipher.DECRYPT_MODE,
                            key.value,
                            GCMParameterSpec(GCM_TAG_BYTES * 8, nonce),
                        )
                        cipher.updateAAD(aad)
                        recovered = cipher.doFinal(ciphertext)
                        if (recovered.isEmpty() || recovered.size > MAX_LOCATOR_BYTES) {
                            openFailure(PrivilegedWorkspaceBindingErrorCode.CRYPTOGRAPHIC_FAILURE)
                        } else {
                            keepRecovered = true
                            PrivilegedWorkspaceBindingOpenResult.Success(recovered)
                        }
                    }
                }
            } catch (_: AEADBadTagException) {
                openFailure(PrivilegedWorkspaceBindingErrorCode.AUTHENTICATION_FAILED)
            } catch (_: BadPaddingException) {
                openFailure(PrivilegedWorkspaceBindingErrorCode.AUTHENTICATION_FAILED)
            } catch (_: GeneralSecurityException) {
                openFailure(PrivilegedWorkspaceBindingErrorCode.CRYPTOGRAPHIC_FAILURE)
            } catch (_: RuntimeException) {
                openFailure(PrivilegedWorkspaceBindingErrorCode.CRYPTOGRAPHIC_FAILURE)
            } finally {
                aad.fill(0)
                expectedDigest.fill(0)
                storedDigest.fill(0)
                nonce.fill(0)
                ciphertext.fill(0)
                if (!keepRecovered) recovered.fill(0)
            }
        }
    }

    private fun openParts(
        encryptedLocator: ByteArray,
        locatorNonce: ByteArray,
        binding: PrivilegedWorkspaceBindingAad,
    ): PrivilegedWorkspaceBindingOpenResult {
        if (
            locatorNonce.size != NONCE_BYTES ||
            encryptedLocator.size !in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES
        ) {
            return openFailure(PrivilegedWorkspaceBindingErrorCode.ENVELOPE_INVALID)
        }
        val aad = try {
            canonicalAad(binding)
        } catch (_: RuntimeException) {
            return openFailure(PrivilegedWorkspaceBindingErrorCode.INVALID_INPUT)
        }
        val nonce = locatorNonce.copyOf()
        val ciphertext = encryptedLocator.copyOf()
        var recovered = ByteArray(0)
        var keepRecovered = false
        return synchronized(monitor) {
            try {
                when (val key = key(create = false)) {
                    is KeyLookup.Failure -> PrivilegedWorkspaceBindingOpenResult.Failure(
                        PrivilegedWorkspaceBindingError(key.code),
                    )
                    is KeyLookup.Success -> {
                        val cipher = Cipher.getInstance(TRANSFORMATION)
                        cipher.init(
                            Cipher.DECRYPT_MODE,
                            key.value,
                            GCMParameterSpec(GCM_TAG_BYTES * 8, nonce),
                        )
                        cipher.updateAAD(aad)
                        recovered = cipher.doFinal(ciphertext)
                        if (recovered.isEmpty() || recovered.size > MAX_LOCATOR_BYTES) {
                            openFailure(PrivilegedWorkspaceBindingErrorCode.CRYPTOGRAPHIC_FAILURE)
                        } else {
                            keepRecovered = true
                            PrivilegedWorkspaceBindingOpenResult.Success(recovered)
                        }
                    }
                }
            } catch (_: AEADBadTagException) {
                openFailure(PrivilegedWorkspaceBindingErrorCode.AUTHENTICATION_FAILED)
            } catch (_: BadPaddingException) {
                openFailure(PrivilegedWorkspaceBindingErrorCode.AUTHENTICATION_FAILED)
            } catch (_: GeneralSecurityException) {
                openFailure(PrivilegedWorkspaceBindingErrorCode.CRYPTOGRAPHIC_FAILURE)
            } catch (_: RuntimeException) {
                openFailure(PrivilegedWorkspaceBindingErrorCode.CRYPTOGRAPHIC_FAILURE)
            } finally {
                aad.fill(0)
                nonce.fill(0)
                ciphertext.fill(0)
                if (!keepRecovered) recovered.fill(0)
            }
        }
    }

    private fun canonicalAad(binding: PrivilegedWorkspaceBindingAad): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.write(AAD_DOMAIN)
        data.writeInt(AAD_FORMAT_VERSION)
        writeUtf8(data, binding.appInstanceId)
        writeUtf8(data, binding.workspaceId)
        writeUtf8(data, binding.authority.name)
        data.writeInt(binding.locatorVersion)
        return output.toByteArray()
    }

    private fun writeUtf8(data: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try {
            data.writeInt(bytes.size)
            data.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private fun key(create: Boolean): KeyLookup {
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (_: GeneralSecurityException) {
            return KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_UNAVAILABLE)
        } catch (_: RuntimeException) {
            return KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_UNAVAILABLE)
        }

        val existing: Key? = try {
            keyStore.getKey(keyAlias, null)
        } catch (_: GeneralSecurityException) {
            return KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_INVALID)
        } catch (_: RuntimeException) {
            return KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_INVALID)
        }
        if (existing != null) {
            val secret = existing as? SecretKey
                ?: return KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_INVALID)
            if (!secret.algorithm.equals(KeyProperties.KEY_ALGORITHM_AES, ignoreCase = true)) {
                return KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_INVALID)
            }
            val keySize = try {
                (SecretKeyFactory.getInstance(secret.algorithm, ANDROID_KEYSTORE)
                    .getKeySpec(secret, KeyInfo::class.java) as KeyInfo).keySize
            } catch (_: GeneralSecurityException) {
                return KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_INVALID)
            } catch (_: RuntimeException) {
                return KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_INVALID)
            }
            if (keySize != KEY_BYTES * 8) {
                return KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_INVALID)
            }
            return KeyLookup.Success(secret)
        }
        if (!create) return KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_UNAVAILABLE)

        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BYTES * 8)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            KeyLookup.Success(generator.generateKey())
        } catch (_: GeneralSecurityException) {
            KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_UNAVAILABLE)
        } catch (_: RuntimeException) {
            KeyLookup.Failure(PrivilegedWorkspaceBindingErrorCode.KEY_UNAVAILABLE)
        }
    }

    private sealed interface KeyLookup {
        data class Success(val value: SecretKey) : KeyLookup
        data class Failure(val code: PrivilegedWorkspaceBindingErrorCode) : KeyLookup
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val MAX_LOCATOR_BYTES = 64 * 1024
        private const val MAX_CIPHERTEXT_BYTES = MAX_LOCATOR_BYTES + GCM_TAG_BYTES
        private const val AAD_FORMAT_VERSION = 1
        private val AAD_DOMAIN = "MAR-PRIVILEGED-WORKSPACE-BINDING-AAD".toByteArray(StandardCharsets.US_ASCII)
        private const val DEFAULT_KEY_ALIAS = "runtime.mobileagent.privileged.workspace.locator.v1"
        private val LOCKS = ConcurrentHashMap<String, Any>()

        private fun sealFailure(code: PrivilegedWorkspaceBindingErrorCode) =
            PrivilegedWorkspaceBindingSealResult.Failure(PrivilegedWorkspaceBindingError(code))

        private fun openFailure(code: PrivilegedWorkspaceBindingErrorCode) =
            PrivilegedWorkspaceBindingOpenResult.Failure(PrivilegedWorkspaceBindingError(code))
    }
}

/** Production construction seam; no plaintext or in-memory fallback exists. */
object AndroidKeystorePrivilegedWorkspaceBindingCipherFactory {
    @JvmStatic
    fun create(): PrivilegedWorkspaceBindingCipher = PrivilegedWorkspaceBindingCipher()
}
