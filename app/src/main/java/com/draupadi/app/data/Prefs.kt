package com.draupadi.app.data

import android.content.Context
import android.content.SharedPreferences

/** One trusted person. Kept deliberately dumb: a name and a number. */
data class Contact(val name: String, val number: String) {
    fun encode() = "${name.replace('|', ' ').replace(';', ' ')}|$number"

    companion object {
        fun decode(s: String): Contact? {
            val p = s.split("|")
            return if (p.size == 2 && p[1].isNotBlank()) Contact(p[0], p[1]) else null
        }
    }
}

/**
 * Everything the app remembers. SharedPreferences on purpose — a woman in
 * trouble should never be waiting on a database migration.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("draupadi", Context.MODE_PRIVATE)

    var setupDone: Boolean
        get() = sp.getBoolean(K_SETUP, false)
        set(v) = sp.edit().putBoolean(K_SETUP, v).apply()

    var name: String
        get() = sp.getString(K_NAME, "") ?: ""
        set(v) = sp.edit().putString(K_NAME, v.trim()).apply()

    var safeWord: String
        get() = sp.getString(K_WORD, "draupadi") ?: "draupadi"
        set(v) = sp.edit().putString(K_WORD, v.trim().lowercase()).apply()

    var policeNumber: String
        get() = sp.getString(K_POLICE, "112") ?: "112"
        set(v) = sp.edit().putString(K_POLICE, v.trim()).apply()

    var guardianOn: Boolean
        get() = sp.getBoolean(K_GUARDIAN, true)
        set(v) = sp.edit().putBoolean(K_GUARDIAN, v).apply()

    var voiceOn: Boolean
        get() = sp.getBoolean(K_VOICE, true)
        set(v) = sp.edit().putBoolean(K_VOICE, v).apply()

    var shakeOn: Boolean
        get() = sp.getBoolean(K_SHAKE, true)
        set(v) = sp.edit().putBoolean(K_SHAKE, v).apply()

    /** Off by default: a blaring alarm also ruins the evidence audio. */
    var loudSiren: Boolean
        get() = sp.getBoolean(K_SIREN, false)
        set(v) = sp.edit().putBoolean(K_SIREN, v).apply()

    /** Silent mode: no siren, no screen change. For a club or a cab. */
    var silentOn: Boolean
        get() = sp.getBoolean(K_SILENT, false)
        set(v) = sp.edit().putBoolean(K_SILENT, v).apply()

    var respondOn: Boolean
        get() = sp.getBoolean(K_RESPOND, true)
        set(v) = sp.edit().putBoolean(K_RESPOND, v).apply()

    var contacts: List<Contact>
        get() = (sp.getString(K_CONTACTS, "") ?: "")
            .split(";;")
            .mapNotNull { if (it.isBlank()) null else Contact.decode(it) }
        set(v) = sp.edit().putString(K_CONTACTS, v.joinToString(";;") { it.encode() }).apply()

    fun addContact(c: Contact) {
        val list = contacts.toMutableList()
        if (list.none { it.number == c.number }) list.add(c)
        contacts = list
    }

    fun removeContact(number: String) {
        contacts = contacts.filterNot { it.number == number }
    }

    private companion object {
        const val K_SETUP = "setup_done"
        const val K_NAME = "name"
        const val K_WORD = "safe_word"
        const val K_POLICE = "police"
        const val K_GUARDIAN = "guardian_on"
        const val K_VOICE = "voice_on"
        const val K_SHAKE = "shake_on"
        const val K_SILENT = "silent_on"
        const val K_SIREN = "loud_siren"
        const val K_RESPOND = "respond_on"
        const val K_CONTACTS = "contacts"
    }
}
