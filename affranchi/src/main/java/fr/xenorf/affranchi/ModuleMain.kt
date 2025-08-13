package fr.xenorf.affranchi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.app.admin.DevicePolicyManager
import androidx.core.app.NotificationCompat

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker

private lateinit var module: ModuleMain

private const val CHANNEL_ID = "lsposed_channel"
private const val CHANNEL_NAME = "LSPosed Notifications"
private const val NOTIF_ID_BASE = 1234

class ModuleMain(base: XposedInterface, param: ModuleLoadedParam) : XposedModule(base, param) {

    init {
        log("ModuleMain loaded in: ${param.processName}")
        module = this
    }

    // ----------------------- Shared helpers -----------------------

    /** Try to get an Application Context from the current process. */
    private fun getAppContext(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentApp = atClass.getDeclaredMethod("currentApplication").invoke(null) as? Application
            currentApp?.applicationContext ?: run {
                // Fallback: AppGlobals.getInitialApplication() (older platforms)
                val ag = Class.forName("android.app.AppGlobals")
                val initialApp = ag.getDeclaredMethod("getInitialApplication").invoke(null) as? Application
                initialApp?.applicationContext
            }
        } catch (t: Throwable) {
            log("getAppContext error: ${t.message}")
            null
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }
    }

    private fun postNotification(title: String, text: String, idOffset: Int) {
        val ctx = getAppContext() ?: run {
            log("No application context; not posting notification: $title")
            return
        }
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm)

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)

            nm.notify(NOTIF_ID_BASE + idOffset, builder.build())
        } catch (e: Exception) {
            log("Failed to post notification: ${e.message}")
        }
    }

    /** Generic utility to hook all overloads of a DPM method name with a given Hooker class. */
    private fun hookAllByName(methodName: String, hooker: Class<out XposedInterface.Hooker>) {
        val matches = DevicePolicyManager::class.java.declaredMethods.filter { it.name == methodName }
        if (matches.isEmpty()) {
            log("No DevicePolicyManager.$methodName() overloads found")
            return
        }
        matches.forEach { m ->
            try {
                deoptimize(m)
                hook(m, hooker)
                log("Hooked DevicePolicyManager.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            } catch (t: Throwable) {
                log("Failed hooking $methodName: ${t.message}")
            }
        }
    }

    // Convenience wrapper used by hookers
    private fun notifyAndSkip(callback: BeforeHookCallback, op: String, skipReturn: Any?, idOffset: Int) {
        val (title, msg) = when (op) {
            "lockNow" -> "Lock prevented" to "Administrator is trying to lock your device."
            "wipeData", "wipeDevice" -> "Wipe prevented" to "Administrator is trying to wipe/reset your device."
            "resetPasswordWithToken" -> "Password reset blocked" to "Administrator is trying to reset your password."
            else -> "Action blocked" to "Administrator is trying to perform: $op"
        }
        postNotification(title, msg, idOffset)
        callback.returnAndSkip(skipReturn)
    }

    // ----------------------- Hookers (one per method) -----------------------

    @XposedHooker
    class LockNowHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            fun before(callback: BeforeHookCallback): LockNowHooker {
                module.log("Before DevicePolicyManager.lockNow()")
                module.notifyAndSkip(callback, "lockNow", null, idOffset = 0) // void -> null
                return LockNowHooker()
            }

            @JvmStatic
            @AfterInvocation
            fun after(callback: AfterHookCallback, ctx: LockNowHooker) {
                module.log("After DevicePolicyManager.lockNow()")
            }
        }
    }

    @XposedHooker
    class WipeDataHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            fun before(callback: BeforeHookCallback): WipeDataHooker {
                module.log("Before DevicePolicyManager.wipeData(...)")
                module.notifyAndSkip(callback, "wipeData", null, idOffset = 1) // void -> null
                return WipeDataHooker()
            }

            @JvmStatic
            @AfterInvocation
            fun after(callback: AfterHookCallback, ctx: WipeDataHooker) {
                module.log("After DevicePolicyManager.wipeData(...)")
            }
        }
    }

    // For Android 14+ where wipeDevice(int) exists; safe to hook if present.
    @XposedHooker
    class WipeDeviceHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            fun before(callback: BeforeHookCallback): WipeDeviceHooker {
                module.log("Before DevicePolicyManager.wipeDevice(...)")
                module.notifyAndSkip(callback, "wipeDevice", null, idOffset = 2) // void -> null
                return WipeDeviceHooker()
            }

            @JvmStatic
            @AfterInvocation
            fun after(callback: AfterHookCallback, ctx: WipeDeviceHooker) {
                module.log("After DevicePolicyManager.wipeDevice(...)")
            }
        }
    }

    @XposedHooker
    class ResetPasswordWithTokenHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            fun before(callback: BeforeHookCallback): ResetPasswordWithTokenHooker {
                module.log("Before DevicePolicyManager.resetPasswordWithToken(...)")
                module.notifyAndSkip(callback, "resetPasswordWithToken", false, idOffset = 3) // boolean -> false
                return ResetPasswordWithTokenHooker()
            }

            @JvmStatic
            @AfterInvocation
            fun after(callback: AfterHookCallback, ctx: ResetPasswordWithTokenHooker) {
                module.log("After DevicePolicyManager.resetPasswordWithToken(...)")
            }
        }
    }

    // ----------------------- Entry point -----------------------

    override fun onPackageLoaded(param: PackageLoadedParam) {
        log("onPackageLoaded: ${param.packageName}")

        // Only target the DPC you care about
        if (param.packageName != "com.google.android.apps.work.clouddpc") return

        try {
            hookAllByName("lockNow", LockNowHooker::class.java)
            hookAllByName("wipeData", WipeDataHooker::class.java)
            hookAllByName("resetPasswordWithToken", ResetPasswordWithTokenHooker::class.java)
            // Optional (Android 14+): hook if present
            hookAllByName("wipeDevice", WipeDeviceHooker::class.java)
        } catch (t: Throwable) {
            log("Error setting hooks: ${t.message}")
        }
    }
}
