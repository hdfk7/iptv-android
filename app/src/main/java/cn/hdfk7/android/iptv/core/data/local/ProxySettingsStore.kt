package cn.hdfk7.android.iptv.core.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import cn.hdfk7.android.iptv.core.model.ProxySettings
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProxySettingsStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "proxy-settings.bin"))
    @Volatile var current: ProxySettings? = read()
        private set

    @Synchronized fun save(settings: ProxySettings) {
        require(settings.valid())
        val json = JSONObject().put("enabled", settings.enabled).put("host", settings.host).put("port", settings.port).put("username", settings.username).put("password", settings.password)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            current = settings
        } catch (exception: Exception) {
            file.failWrite(stream)
            throw exception
        }
    }

    private fun read(): ProxySettings? {
        return runCatching(block = {
            val bytes = try {
                file.readFully()
            } catch (exception: FileNotFoundException) {
                if (file.baseFile.exists()) {
                    throw exception
                }
                return ProxySettings()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            ProxySettings(json.getBoolean("enabled"), json.getString("host"), json.getString("port"), json.getString("username"), json.getString("password")).also(block = { require(it.valid()) })
        }).getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply(block = { load(null) })
        val existing = store.getKey("iptv-proxy", null) as? SecretKey
        if (existing != null) {
            return existing
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply(block = {
            init(KeyGenParameterSpec.Builder("iptv-proxy", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }).generateKey()
    }
}
