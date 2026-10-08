/*
 * Copyright 2026 Locus Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.locus.core.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EncryptedSecretStore persists API keys, tokens, and credentials encrypted at rest (P-6, SEC-3).
 *
 * Uses [EncryptedSharedPreferences] backed by an AndroidKeyStore master key.
 * Secrets reside exclusively in app-private storage outside the SAF-managed document tree,
 * preventing accidental inclusion in standard note backups or exports.
 */
@Singleton
class EncryptedSecretStore(
    private val sharedPreferences: SharedPreferences,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(createEncryptedPreferences(context))

    /**
     * Retrieves an encrypted secret by [key].
     * // SEC-3: never log
     */
    fun getSecret(key: String): String? = sharedPreferences.getString(key, null)

    /**
     * Stores an encrypted [secret] under [key]. If [secret] is null or empty, the key is removed.
     * // SEC-3: never log
     */
    fun setSecret(
        key: String,
        secret: String?,
    ) {
        val editor = sharedPreferences.edit()
        if (secret.isNullOrEmpty()) {
            editor.remove(key)
        } else {
            editor.putString(key, secret)
        }
        editor.commit()
    }

    /**
     * Removes an encrypted secret by [key].
     */
    fun removeSecret(key: String) {
        sharedPreferences.edit().remove(key).commit()
    }

    /**
     * Returns whether an encrypted secret exists for [key].
     */
    fun hasSecret(key: String): Boolean = sharedPreferences.contains(key)

    /**
     * Returns all stored secrets.
     * // SEC-3: never log
     */
    fun getAllSecrets(): Map<String, String> {
        val allEntries = sharedPreferences.all
        val secrets = mutableMapOf<String, String>()
        for ((k, v) in allEntries) {
            if (v is String) {
                secrets[k] = v
            }
        }
        return secrets
    }

    /**
     * Clears all secrets from encrypted storage.
     */
    fun clear() {
        sharedPreferences.edit().clear().apply()
    }

    companion object {
        const val PREFS_FILE_NAME = "locus_encrypted_secrets"

        @Suppress("DEPRECATION")
        fun createEncryptedPreferences(context: Context): SharedPreferences {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
