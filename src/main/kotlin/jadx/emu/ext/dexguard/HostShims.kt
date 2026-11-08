package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.DvmClass
import jadx.plugins.emu.exec.DvmField
import jadx.plugins.emu.exec.DvmMethodHandle
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.isUnknown
import java.io.ByteArrayInputStream

internal class HostShims(private val app: AppInfo) {
    fun register(hooks: Hooks) {
        registerFrameworkMembers(hooks)
        registerArchiveAccess(hooks)
        hooks.stub("Ljava/lang/System;->identityHashCode") { replace(System.identityHashCode(args.getOrNull(0))) }
        hooks.hook("Ljava/lang/System;->getProperty(Ljava/lang/String;)Ljava/lang/String;") {
            SYSTEM_PROPERTIES[args.getOrNull(0)]?.let { replace(it.replace("{pkg}", app.packageName ?: "app")) }
        }
    }

    private fun registerFrameworkMembers(hooks: Hooks) {
        for (getter in FIELD_GETTERS) {
            hooks.hook("Ljava/lang/reflect/Field;->$getter") {
                val f = receiver as? DvmField ?: return@hook
                if (!hooks.isFrameworkClass(f.ref.declClass)) return@hook
                val v = vm.getStatic("${f.ref.declClass}->${f.ref.name}")
                if (!v.isUnknown()) replace(v)
            }
        }
        hooks.hook(METHOD_INVOKE) {
            val h = receiver as? DvmMethodHandle ?: return@hook
            val target = args.getOrNull(0)?.takeUnless { it == 0 }
            val callArgs = argList(args.getOrNull(1))
            if (h.hostMethod != null) return@hook
            val symbol = h.symbol ?: return@hook
            if (!hooks.isFrameworkClass(symbol.declClass)) return@hook
            if (target == null) return@hook
            val r = runCatching { vm.invoke(target, symbol.shortId, callArgs) }.getOrNull()
            if (r != null && !r.isUnknown()) replace(r)
        }
    }

    private fun registerArchiveAccess(hooks: Hooks) {
        hooks.hook(METHOD_INVOKE) {
            val m = (receiver as? DvmMethodHandle)?.hostMethod ?: return@hook
            if (m.declaringClass != Class::class.java || args.getOrNull(0) !is DvmClass) return@hook
            val name = argList(args.getOrNull(1)).getOrNull(0)?.toString() ?: return@hook
            when (m.name) {
                "getResource" -> replace(app.resourceUrl(name))
                "getResourceAsStream" -> replace(app.resourceBytes(name)?.let { ByteArrayInputStream(it) })
            }
        }
        for (ctor in listOf("(Ljava/lang/String;)V", "(Ljava/io/File;)V", "(Ljava/io/File;I)V", "(Ljava/lang/String;Ljava/nio/charset/Charset;)V")) {
            hooks.hook("Ljava/util/zip/ZipFile;-><init>$ctor") { replace(null) }
        }
        hooks.hook("Ljava/util/zip/ZipFile;->getEntry(Ljava/lang/String;)Ljava/util/zip/ZipEntry;") {
            val name = args.getOrNull(0) as? String ?: return@hook
            val bytes = app.resourceBytes(name) ?: return@hook replace(null)
            replace(DvmObject(ZIP_ENTRY).apply { fields[ZIP_ENTRY_NAME] = name; fields[ZIP_ENTRY_SIZE] = bytes.size.toLong() })
        }
        hooks.hook("Ljava/util/zip/ZipFile;->getInputStream(Ljava/util/zip/ZipEntry;)Ljava/io/InputStream;") {
            val entry = args.getOrNull(0) as? DvmObject ?: return@hook
            val name = entry.fields[ZIP_ENTRY_NAME] as? String ?: return@hook
            app.resourceBytes(name)?.let { replace(ByteArrayInputStream(it)) }
        }
        hooks.hook("Ljava/util/zip/ZipFile;->close()V") { replace(null) }
        hooks.stub("$ZIP_ENTRY->getName") { (receiver as? DvmObject)?.fields?.get(ZIP_ENTRY_NAME)?.let { replace(it) } }
        hooks.stub("$ZIP_ENTRY->getSize") { (receiver as? DvmObject)?.fields?.get(ZIP_ENTRY_SIZE)?.let { replace(it) } }
    }

    private fun argList(v: Any?): List<Any?> = (v as? Array<*>)?.toList() ?: emptyList()

    companion object {
        const val METHOD_INVOKE = "Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"
        private const val ZIP_ENTRY = "Ljava/util/zip/ZipEntry;"
        private const val ZIP_ENTRY_NAME = "$ZIP_ENTRY.name"
        private const val ZIP_ENTRY_SIZE = "$ZIP_ENTRY.size"
        private val FIELD_GETTERS = listOf(
            "get(Ljava/lang/Object;)Ljava/lang/Object;", "getInt(Ljava/lang/Object;)I", "getLong(Ljava/lang/Object;)J",
            "getBoolean(Ljava/lang/Object;)Z", "getShort(Ljava/lang/Object;)S", "getByte(Ljava/lang/Object;)B",
            "getChar(Ljava/lang/Object;)C", "getFloat(Ljava/lang/Object;)F", "getDouble(Ljava/lang/Object;)D",
        )
        private val SYSTEM_PROPERTIES = mapOf(
            "java.io.tmpdir" to "/data/data/{pkg}/cache",
            "java.vm.version" to "2.1.0",
            "os.arch" to "aarch64",
            "user.dir" to "/",
        )
    }
}
