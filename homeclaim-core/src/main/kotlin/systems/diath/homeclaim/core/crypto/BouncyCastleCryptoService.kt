package systems.diath.homeclaim.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.digests.Blake2bDigest
import systems.diath.homeclaim.core.model.CryptoProfile
import systems.diath.homeclaim.core.service.CryptoService
import java.security.SecureRandom
import java.util.Base64

/**
 * CryptoService implementation using Bouncy Castle
 * 
 * Password hashing: Argon2id (memory-hard, GPU-resistant)
 * Token hashing: BLAKE2b (fast, secure)
 */
class BouncyCastleCryptoService : CryptoService {

    private val secureRandom = SecureRandom()

    // ============ PASSWORD HASHING (Argon2id) ============

    override fun hashPassword(password: String, profile: CryptoProfile): String {
        val salt = generateSalt(16)
        val hash = argon2Hash(password.toByteArray(Charsets.UTF_8), salt, profile)
        
        // Format: $argon2id$profile$salt$hash
        val saltB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
        val hashB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        
        return "\$argon2id\$${profile.name}\$$saltB64\$$hashB64"
    }

    override fun verifyPassword(password: String, hash: String): Boolean {
        try {
            val parts = hash.split("$")
            if (parts.size != 5 || parts[1] != "argon2id") return false
            
            val profile = CryptoProfile.valueOf(parts[2])
            val salt = Base64.getUrlDecoder().decode(parts[3])
            val expectedHash = Base64.getUrlDecoder().decode(parts[4])
            
            val computedHash = argon2Hash(password.toByteArray(Charsets.UTF_8), salt, profile)
            
            return constantTimeEquals(expectedHash, computedHash)
        } catch (e: Exception) {
            return false
        }
    }

    override fun needsRehash(hash: String, targetProfile: CryptoProfile): Boolean {
        try {
            val parts = hash.split("$")
            if (parts.size != 5 || parts[1] != "argon2id") return true
            
            val currentProfile = CryptoProfile.valueOf(parts[2])
            return currentProfile != targetProfile
        } catch (e: Exception) {
            return true
        }
    }

    private fun argon2Hash(password: ByteArray, salt: ByteArray, profile: CryptoProfile): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(profile.memoryCostKiB)
            .withIterations(profile.timeCost)
            .withParallelism(profile.parallelism)
            .build()
        
        val generator = Argon2BytesGenerator()
        generator.init(params)
        
        val hash = ByteArray(profile.hashLength)
        generator.generateBytes(password, hash)
        
        return hash
    }

    // ============ TOKEN HASHING (BLAKE2b) ============

    // Fixed key for token hashing (used when no explicit salt is provided)
    private val tokenHashKey = "HomeClaim-TokenHash-v1".toByteArray(Charsets.UTF_8)

    override fun generateToken(length: Int): String {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun hashToken(token: String): String {
        return hashToken(token, tokenHashKey)
    }

    override fun hashToken(token: String, salt: ByteArray): String {
        val digest = Blake2bDigest(256) // 256-bit output
        
        // Update with salt + token
        digest.update(salt, 0, salt.size)
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        digest.update(tokenBytes, 0, tokenBytes.size)
        
        val hash = ByteArray(32)
        digest.doFinal(hash, 0)
        
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    override fun verifyToken(token: String, hash: String): Boolean {
        return verifyToken(token, hash, tokenHashKey)
    }

    override fun verifyToken(token: String, hash: String, salt: ByteArray): Boolean {
        val computedHash = hashToken(token, salt)
        return constantTimeEquals(hash.toByteArray(), computedHash.toByteArray())
    }

    override fun generateSalt(length: Int): ByteArray {
        val salt = ByteArray(length)
        secureRandom.nextBytes(salt)
        return salt
    }

    // ============ TOTP ============

    override fun generateTotpSecret(): ByteArray {
        // 20 bytes = 160 bits for TOTP secret
        val secret = ByteArray(20)
        secureRandom.nextBytes(secret)
        return secret
    }

    override fun generateTotpCode(secret: ByteArray, time: Long): String {
        val timeStep = time / 30000 // 30-second window
        val counter = ByteArray(8)
        var value = timeStep
        for (i in 7 downTo 0) {
            counter[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        
        // HMAC-SHA1
        val hmac = hmacSha1(secret, counter)
        
        // Dynamic truncation
        val offset = hmac[hmac.size - 1].toInt() and 0x0F
        val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
                     ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                     ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                     (hmac[offset + 3].toInt() and 0xFF)
        
        val otp = binary % 1000000
        return otp.toString().padStart(6, '0')
    }

    override fun verifyTotpCode(secret: ByteArray, code: String, windowSize: Int): Boolean {
        val currentTime = System.currentTimeMillis()
        
        for (i in -windowSize..windowSize) {
            val time = currentTime + (i * 30000)
            val expectedCode = generateTotpCode(secret, time)
            if (constantTimeEquals(code.toByteArray(), expectedCode.toByteArray())) {
                return true
            }
        }
        
        return false
    }

    override fun generateBackupCodes(count: Int): List<String> {
        return (1..count).map {
            val bytes = ByteArray(5)
            secureRandom.nextBytes(bytes)
            bytes.joinToString("") { b -> String.format("%02x", b) }.uppercase()
        }
    }

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    /**
     * Constant-time comparison to prevent timing attacks
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
