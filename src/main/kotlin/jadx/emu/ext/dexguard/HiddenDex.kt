package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.EmuContext
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.DvmClass
import jadx.plugins.emu.exec.DvmCtorHandle
import jadx.plugins.emu.exec.DvmField
import jadx.plugins.emu.exec.DvmMethodHandle
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.plus
import jadx.plugins.emu.exec.runtime.DvmObject
import org.slf4j.Logger
import java.nio.ByteBuffer
import java.security.MessageDigest

internal class HiddenDex(
    private val emu: EmuContext,
    private val hooks: Hooks,
    private val limits: ExecLimits,
    private val log: Logger,
    private val onLoad: (ByteArray, MethodSource) -> Unit,
) {
    private val loaders: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private val pathLists = java.util.IdentityHashMap<Any, DvmObject>()
    private val appLoader: Any by lazy { newLoader() }
    private val sources = LinkedHashMap<String, MethodSource>()
    private var hiddenVm: Vm? = null
    private val composite: MethodSource get() = sources.values.fold(emu.source) { acc, s -> s + acc }

    fun register() {
        hooks.hook(CTOR_NEW_INSTANCE) {
            val h = receiver as? DvmCtorHandle ?: return@hook
            if (h.owner !in LOADER_CLASSES) return@hook
            val callArgs = (args.getOrNull(0) as? Array<*>)?.toList() ?: emptyList()
            val dexes = dexBytesOf(callArgs)
            if (dexes.isEmpty()) return@hook
            if (dexes.count { load(it) } > 0 || dexes.any { key(it) in sources }) replace(newLoader())
        }
        hooks.hook("Ljava/lang/ClassLoader;->loadClass(Ljava/lang/String;)Ljava/lang/Class;") {
            if (receiver?.let { isLoader(it) } != true) return@hook
            val name = args.getOrNull(0) as? String ?: return@hook
            replace(classNamed(name))
        }
        hooks.hook(HostShims.METHOD_INVOKE) {
            val h = receiver as? DvmMethodHandle ?: return@hook
            val hm = h.hostMethod
            if (hm != null) {
                val target = args.getOrNull(0)
                val callArgs = (args.getOrNull(1) as? Array<*>)?.toList() ?: emptyList()
                when {
                    hm.declaringClass == Class::class.java && hm.name == "getClassLoader" && target is DvmClass -> replace(appLoader)
                    hm.name == "loadClass" && target != null && isLoader(target) ->
                        (callArgs.getOrNull(0) as? String)?.let { replace(classNamed(it)) }
                }
                return@hook
            }
            val symbol = h.symbol ?: return@hook
            val target = args.getOrNull(0)?.takeUnless { it == 0 }
            val callArgs = (args.getOrNull(1) as? Array<*>)?.toList() ?: emptyList()
            val m = resolve(symbol.declClass, symbol.name, symbol.argTypes) ?: return@hook
            replace(vm().invoke(m, callArgs, if (m.isStatic) null else target))
        }
        hooks.hook(CTOR_NEW_INSTANCE) {
            val h = receiver as? DvmCtorHandle ?: return@hook
            if (!isHidden(h.owner)) return@hook
            val callArgs = (args.getOrNull(0) as? Array<*>)?.toList() ?: emptyList()
            val cands = composite.methodsByName(h.owner, "<init>")
            val init = cands.firstOrNull { it.ref.argTypes == h.params } ?: cands.firstOrNull { it.ref.argTypes.size == callArgs.size }
            if (init == null) return@hook
            val obj = DvmObject(h.owner)
            vm().invoke(init, callArgs, obj)
            replace(obj)
        }
        for (getter in GETTERS) {
            hooks.hook("Ljava/lang/reflect/Field;->$getter") {
                val f = receiver as? DvmField ?: return@hook
                if (f.ref.declClass == BASE_LOADER && f.ref.name == "pathList") {
                    val target = args.getOrNull(0)
                    if (target != null && target in loaders) replace(pathListOf(target))
                    return@hook
                }
                if (!isHidden(f.ref.declClass)) return@hook
                val meta = composite.classInfo(f.ref.declClass)?.fields?.firstOrNull { it.ref.name == f.ref.name } ?: return@hook
                val v = if (meta.isStatic) {
                    vm().getStatic("${f.ref.declClass}->${f.ref.name}")
                } else {
                    (args.getOrNull(0) as? DvmObject)?.fields?.get(meta.ref.key)
                }
                replace(v)
            }
        }
    }

    val loadedCount: Int get() = sources.size

    fun isHidden(desc: String): Boolean = sources.values.any { it.classInfo(desc) != null }

    private fun isLoader(v: Any): Boolean = v in loaders || (v is DvmObject && extendsHost(v.type, ClassLoader::class.java))

    private fun extendsHost(type: String, host: Class<*>): Boolean {
        var cur: String? = type
        while (cur != null) {
            val info = composite.classInfo(cur) ?: return runCatching { host.isAssignableFrom(HostClasses.of(cur)) }.getOrDefault(false)
            cur = info.superType
        }
        return false
    }

    private fun classNamed(name: String): DvmClass? {
        val desc = "L" + name.replace('.', '/') + ";"
        return if (composite.classInfo(desc) != null) DvmClass(desc) else null
    }

    private fun newLoader(): Any = java.net.URLClassLoader(emptyArray()).also { loaders += it }

    private fun pathListOf(loader: Any): DvmObject = pathLists.getOrPut(loader) {
        DvmObject(PATH_LIST).apply {
            fields["$PATH_LIST.dexElements"] = arrayOfNulls<Any?>(0)
            fields["$PATH_LIST.nativeLibraryPathElements"] = arrayOfNulls<Any?>(0)
            fields["$PATH_LIST.nativeLibraryDirectories"] = ArrayList<Any?>()
            fields["$PATH_LIST.systemNativeLibraryDirectories"] = ArrayList<Any?>()
            fields["$PATH_LIST.definingContext"] = loader
        }
    }

    private fun vm(): Vm = hiddenVm ?: emu.fork(composite, hooks, limits).newVm(limits).also { hiddenVm = it }

    private fun resolve(owner: String, name: String, argTypes: List<String>): DexMethod? {
        if (!isHidden(owner)) return null
        var cur: String? = owner
        while (cur != null) {
            val cands = composite.methodsByName(cur, name)
            cands.firstOrNull { it.ref.argTypes == argTypes }?.let { return it }
            cur = composite.classInfo(cur)?.superType
        }
        return null
    }

    private fun load(bytes: ByteArray): Boolean {
        val k = key(bytes)
        if (k in sources) return false
        return try {
            val src = emu.loadDex(bytes)
            sources[k] = src
            hiddenVm = null
            log.info("loaded hidden dex, {} bytes, {} methods", bytes.size, src.allMethods().size)
            onLoad(bytes, src)
            true
        } catch (e: Exception) {
            log.warn("cannot parse hidden dex ({} bytes): {}", bytes.size, e.toString())
            false
        }
    }

    private fun dexBytesOf(args: List<Any?>): List<ByteArray> = args.flatMap { a ->
        when (a) {
            is ByteBuffer -> listOf(bufferBytes(a))
            is ByteArray -> listOf(a)
            is Array<*> -> a.filterIsInstance<ByteBuffer>().map { bufferBytes(it) }
            else -> emptyList()
        }
    }.filter { it.size > 0x70 && it.copyOfRange(0, 4).contentEquals(DEX_MAGIC) }

    private fun bufferBytes(b: ByteBuffer): ByteArray {
        val d = b.duplicate()
        d.position(0)
        return ByteArray(d.limit()).also { d.get(it) }
    }

    private fun key(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val BASE_LOADER = "Ldalvik/system/BaseDexClassLoader;"
        const val PATH_LIST = "Ldalvik/system/DexPathList;"
        const val CTOR_NEW_INSTANCE = "Ljava/lang/reflect/Constructor;->newInstance([Ljava/lang/Object;)Ljava/lang/Object;"
        val LOADER_CLASSES = setOf(
            "Ldalvik/system/InMemoryDexClassLoader;", "Ldalvik/system/DexClassLoader;",
            "Ldalvik/system/PathClassLoader;", "Ldalvik/system/BaseDexClassLoader;",
        )
        val GETTERS = listOf(
            "get(Ljava/lang/Object;)Ljava/lang/Object;", "getInt(Ljava/lang/Object;)I", "getLong(Ljava/lang/Object;)J",
            "getBoolean(Ljava/lang/Object;)Z", "getShort(Ljava/lang/Object;)S", "getByte(Ljava/lang/Object;)B",
            "getChar(Ljava/lang/Object;)C", "getFloat(Ljava/lang/Object;)F", "getDouble(Ljava/lang/Object;)D",
        )
        val DEX_MAGIC = "dex\n".toByteArray()
    }
}
