package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.DvmMethodHandle
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.UNKNOWN

internal class AppContextStubs(private val app: AppInfo) {

    private val context = DvmObject("Landroid/app/Application;")
    private val resources = DvmObject("Landroid/content/res/Resources;")
    private val appInfo by lazy {
        DvmObject("Landroid/content/pm/ApplicationInfo;").apply {
            fields["Landroid/content/pm/ApplicationInfo;.targetSdkVersion"] = app.targetSdkVersion
            fields["Landroid/content/pm/ApplicationInfo;.minSdkVersion"] = 24
            fields["Landroid/content/pm/ApplicationInfo;.packageName"] = app.packageName
            fields["Landroid/content/pm/ApplicationInfo;.flags"] = 0
        }
    }

    fun register(hooks: Hooks) {
        hooks.hook(HostShims.METHOD_INVOKE) {
            val symbol = (receiver as? DvmMethodHandle)?.symbol ?: return@hook
            if (symbol.name in APP_GETTERS[symbol.declClass].orEmpty()) replace(context)
        }
        for (cls in CONTEXT_CLASSES) {
            hooks.stub("$cls->getApplicationContext", context)
            hooks.stub("$cls->getBaseContext", context)
            hooks.stub("$cls->getResources", resources)
            hooks.stub("$cls->getPackageName") { replace(app.packageName ?: UNKNOWN) }
            hooks.stub("$cls->getApplicationInfo") { replace(appInfo) }
            hooks.stub("$cls->getString") { replace(resourceString(args)) }
            hooks.stub("$cls->getText") { replace(resourceString(args)) }
        }
        hooks.stub("Landroid/content/res/Resources;->getString") { replace(resourceString(args)) }
        hooks.stub("Landroid/content/res/Resources;->getText") { replace(resourceString(args)) }
    }

    private fun resourceString(a: List<Any?>): Any? {
        val id = a.getOrNull(0) as? Int ?: return UNKNOWN
        return app.string(id) ?: UNKNOWN
    }

    private companion object {
        val APP_GETTERS = mapOf(
            "Landroid/app/ActivityThread;" to setOf("currentApplication", "currentActivityThread"),
            "Landroid/app/AppGlobals;" to setOf("getInitialApplication"),
        )
        val CONTEXT_CLASSES = listOf(
            "Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/app/Application;",
            "Landroid/app/Activity;", "Landroid/view/ContextThemeWrapper;",
        )
    }
}
