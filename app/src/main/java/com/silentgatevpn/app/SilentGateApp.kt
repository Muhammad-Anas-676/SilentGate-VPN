package com.silentgatevpn.app

import android.app.Application
import android.os.Build
import android.content.Context
import android.telephony.TelephonyManager
import com.silentgatevpn.app.vpn.Net
import com.silentgatevpn.app.vpn.NodePool
import com.silentgatevpn.app.vpn.PrimaryServer
import com.silentgatevpn.app.vpn.ProfileStore
import com.silentgatevpn.app.vpn.SettingsStore
import com.silentgatevpn.app.vpn.VpnState
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class SilentGateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        ProfileStore.init(this)
        PrimaryServer.init(this)
        NodePool.init(File(filesDir, "pool").apply { mkdirs() })
        // Last-resort home country if the live check fails: the SIM / network country, then Pakistan.
        runCatching {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val cc = tm.networkCountryIso.ifEmpty { tm.simCountryIso }
            if (cc.length == 2) Net.defaultHomeCc = cc.uppercase()
        }
        // Keep the node list fresh in the background (not on the main thread). First-time users get the same
        // pre-warm the moment they accept the welcome screen (see ConsentScreen).
        if (SettingsStore.value.consented) NodePool.prewarm()
        runCatching {
            val work = File(filesDir, "work").apply { mkdirs() }
            val opts = SetupOptions()
            opts.setBasePath(filesDir.path)
            opts.setWorkingPath(work.path)
            opts.setTempPath(cacheDir.path)
            opts.setFixAndroidStack(Build.VERSION.SDK_INT > Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            opts.setLogMaxLines(500L)
            opts.setDebug(false)
            Libbox.setup(opts)
        }.onFailure { VpnState.log("Core setup failed: ${it.message}") }
    }
}
