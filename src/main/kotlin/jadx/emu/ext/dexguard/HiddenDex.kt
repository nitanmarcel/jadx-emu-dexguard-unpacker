package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.ClassInfo
import jadx.plugins.emu.exec.DvmClass
import jadx.plugins.emu.exec.DvmCtorHandle
import jadx.plugins.emu.exec.DvmField
import jadx.plugins.emu.exec.DvmMethodHandle
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.HookRegistry
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.runtime.DvmObject
import org.slf4j.Logger
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest

internal class HiddenDex(
    private val base: MethodSource,
    private val engine: EngineContext,
    private val hooks: HookRegistry,
    private val limits: ExecLimits,
    private val log: Logger,
    private val onLoad: (ByteArray, DexInputSource) -> Unit,
) {
    private val loaders: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private val pathLists = java.util.IdentityHashMap<Any, DvmObject>()
    private val appLoader: Any by lazy { newLoader() }
    private val sources = LinkedHashMap<String, DexInputSource>()
    private val composite = Composite()
    private var hiddenVm: Vm? = null

    fun register() {
        hooks.add(CTOR_NEW_INSTANCE) { call ->
            val h = call.receiver as? DvmCtorHandle ?: return@add
            if (h.owner !in LOADER_CLASSES) return@add
            val args = (call.args.getOrNull(0) as? Array<*>)?.toList() ?: emptyList()
            val dexes = dexBytesOf(args)
            if (dexes.isEmpty()) return@add
            if (dexes.count { load(it) } > 0 || dexes.any { key(it) in sources }) call.replace(newLoader())
        }
        hooks.add("Ljava/lang/ClassLoader;->loadClass(Ljava/lang/String;)Ljava/lang/Class;") { call ->
            if (call.receiver?.let { isLoader(it) } != true) return@add
            val name = call.args.getOrNull(0) as? String ?: return@add
            call.replace(classNamed(name))
        }
        hooks.add(HostShims.METHOD_INVOKE) { call ->
            val h = call.receiver as? DvmMethodHandle ?: return@add
            val hm = h.hostMethod
            if (hm != null) {
                val target = call.args.getOrNull(0)
                val args = (call.args.getOrNull(1) as? Array<*>)?.toList() ?: emptyList()
                when {
                    hm.declaringClass == Class::class.java && hm.name == "getClassLoader" && target is DvmClass -> call.replace(appLoader)
                    hm.name == "loadClass" && target != null && isLoader(target) ->
                        (args.getOrNull(0) as? String)?.let { call.replace(classNamed(it)) }
                }
                return@add
            }
            val symbol = h.symbol ?: return@add
            val target = call.args.getOrNull(0)?.takeUnless { it == 0 }
            val args = (call.args.getOrNull(1) as? Array<*>)?.toList() ?: emptyList()
            val m = resolve(symbol.declClass, symbol.name, symbol.argTypes) ?: return@add
            call.replace(vm().invoke(m, args, if (m.isStatic) null else target))
        }
        hooks.add(CTOR_NEW_INSTANCE) { call ->
            val h = call.receiver as? DvmCtorHandle ?: return@add
            if (!isHidden(h.owner)) return@add
            val args = (call.args.getOrNull(0) as? Array<*>)?.toList() ?: emptyList()
            val cands = composite.methodsByName(h.owner, "<init>")
            val init = cands.firstOrNull { it.ref.argTypes == h.params } ?: cands.firstOrNull { it.ref.argTypes.size == args.size }
            if (init == null) return@add
            val obj = DvmObject(h.owner)
            vm().invoke(init, args, obj)
            call.replace(obj)
        }
        for (getter in GETTERS) {
            hooks.add("Ljava/lang/reflect/Field;->$getter") { call ->
                val f = call.receiver as? DvmField ?: return@add
                if (f.ref.declClass == BASE_LOADER && f.ref.name == "pathList") {
                    val target = call.args.getOrNull(0)
                    if (target != null && target in loaders) call.replace(pathListOf(target))
                    return@add
                }
                if (!isHidden(f.ref.declClass)) return@add
                val meta = composite.classInfo(f.ref.declClass)?.fields?.firstOrNull { it.ref.name == f.ref.name } ?: return@add
                val v = if (meta.isStatic) {
                    val vm = vm()
                    vm.ensureClinit(f.ref.declClass)
                    vm.staticsOf(f.ref.declClass)[meta.ref.key]
                } else {
                    (call.args.getOrNull(0) as? DvmObject)?.fields?.get(meta.ref.key)
                }
                call.replace(v)
            }
        }
    }

    val loadedCount: Int get() = sources.size

    fun isHidden(desc: String): Boolean = sources.values.any { it.classInfo(desc) != null }

    private fun isLoader(v: Any): Boolean = v in loaders || (v is DvmObject && extendsHost(v.type, ClassLoader::class.java))

    private fun extendsHost(type: String, host: Class<*>): Boolean {
        var cur: String? = type
        while (cur != null) {
            val info = composite.classInfo(cur) ?: return runCatching { host.isAssignableFrom(jadx.plugins.emu.exec.hostClass(cur)) }.getOrDefault(false)
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

    private fun vm(): Vm = hiddenVm ?: EngineContext(composite, limits, engine.host, engine.android)
        .newVm(limits, hooks = hooks).also { hiddenVm = it }

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
        val tmp = Files.createTempFile("jadx-emu-hidden", ".dex")
        return try {
            Files.write(tmp, bytes)
            val src = DexInputSource.load(tmp.toFile())
            sources[k] = src
            hiddenVm = null
            log.info("loaded hidden dex, {} bytes, {} methods", bytes.size, src.allMethods().size)
            onLoad(bytes, src)
            true
        } catch (e: Exception) {
            log.warn("cannot parse hidden dex ({} bytes): {}", bytes.size, e.toString())
            false
        } finally {
            Files.deleteIfExists(tmp)
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

    private inner class Composite : MethodSource {
        override fun method(classDesc: String, shortId: String): DexMethod? =
            sources.values.firstNotNullOfOrNull { it.method(classDesc, shortId) } ?: base.method(classDesc, shortId)

        override fun classInfo(classDesc: String): ClassInfo? =
            sources.values.firstNotNullOfOrNull { it.classInfo(classDesc) } ?: base.classInfo(classDesc)

        override fun methodsByName(classDesc: String, name: String): List<DexMethod> =
            sources.values.firstOrNull { it.classInfo(classDesc) != null }?.methodsByName(classDesc, name) ?: base.methodsByName(classDesc, name)

        override fun methodsOf(classDesc: String): List<DexMethod> =
            sources.values.firstOrNull { it.classInfo(classDesc) != null }?.methodsOf(classDesc) ?: base.methodsOf(classDesc)

        override fun allMethods(): List<DexMethod> = sources.values.flatMap { it.allMethods() } + base.allMethods()

        override fun isNative(classDesc: String, shortId: String): Boolean =
            sources.values.firstOrNull { it.classInfo(classDesc) != null }?.isNative(classDesc, shortId) ?: base.isNative(classDesc, shortId)
    }

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
