package io.github.victorkolesnyk.hamir.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.victorkolesnyk.hamir.logic.BankStore
import io.github.victorkolesnyk.hamir.logic.KeyValueStore
import io.github.victorkolesnyk.hamir.logic.Observable
import io.github.victorkolesnyk.hamir.logic.ProtectionState
import io.github.victorkolesnyk.hamir.logic.SettingsRepository
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SharedPreferences as a string map. Callers are always on a worker thread, so values are written
 * with commit(): apply() would make the framework flush pending writes on the UI thread when an
 * activity or service stops.
 */
class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun read(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((key, value) in prefs.all) if (value is String) out[key] = value
        return out
    }

    override fun write(changes: Map<String, String?>) {
        val editor = prefs.edit()
        for ((key, value) in changes) {
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }
        editor.commit()
    }
}

/**
 * Process-wide singletons. Everything that touches the disk or the keystore is created lazily
 * and must be used from [io] (or another worker thread), never from the UI thread.
 */
object Deps {
    /** Serial executor for short disk tasks: settings, statistics, single takes. */
    val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hamir-io").apply { isDaemon = true }
    }

    val main: Handler = Handler(Looper.getMainLooper())

    /** State of the protection service, observed by the main screen. Set on the main thread only. */
    val protectionState: Observable<ProtectionState> = Observable(ProtectionState())

    private var bankStore: BankStore? = null
    private var settingsRepository: SettingsRepository? = null

    @Synchronized
    fun bank(context: Context): BankStore {
        bankStore?.let { return it }
        val root = File(context.applicationContext.filesDir, "bank")
        return BankStore(root, KeystoreKeyProvider()).also { bankStore = it }
    }

    @Synchronized
    fun settings(context: Context): SettingsRepository {
        settingsRepository?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences("hamir_settings", Context.MODE_PRIVATE)
        return SettingsRepository(PrefsStore(prefs)).also { settingsRepository = it }
    }
}
