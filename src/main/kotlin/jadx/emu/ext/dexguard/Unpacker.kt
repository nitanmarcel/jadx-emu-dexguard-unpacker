package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.EmuExtensionContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.api.EmuTarget
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.model.DexMethod
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal class Unpacker(private val ctx: EmuExtensionContext) {

    val limits = ExecLimits(maxSteps = 50_000_000, maxMillis = 120_000, maxDepth = 64)

    private val outDir: Path by lazy { ctx.dataDir.resolve(inputHash()).also { Files.createDirectories(it) } }
    private val dumped = ArrayList<Path>()

    fun registerActions() {
        ctx.addCodeAction("DexGuard: unpack from this class", { !it.isMethod }, null) { target ->
            Thread({ unpackFrom(target.rawClassName) }, "dexguard-unpacker").apply { isDaemon = true }.start()
        }
    }

    fun onLoad(bytes: ByteArray, src: MethodSource) {
        val name = "hidden-" + sha256(bytes).substring(0, 12) + ".dex"
        val path = outDir.resolve(name)
        if (!Files.exists(path)) Files.write(path, bytes)
        if (path !in dumped) dumped.add(path)
        ctx.log.info("hidden dex {} ({} classes) written to {}", name, src.allMethods().map { it.declClass }.distinct().size, path)
    }

    private fun unpackFrom(rawClassName: String) {
        val clinit = ctx.emu.method(EmuTarget(rawClassName, "<clinit>()V"))
        if (clinit == null) {
            ctx.log.warn("{} has no static initializer", rawClassName)
            return
        }
        val before = dumped.size
        run(clinit)
        val n = dumped.size - before
        ctx.log.info("{}: {} hidden dex file(s) unpacked", rawClassName, n)
        if (n > 0) offer(dumped.toList())
    }

    private fun run(clinit: DexMethod) {
        val vm = ctx.emu.newVm(limits)
        runCatching { vm.invoke(clinit) }.onFailure { ctx.log.warn("{}: initializer failed: {}", clinit.declClass, it.toString()) }
    }

    private fun offer(paths: List<Path>) {
        val gui = ctx.gui ?: run {
            ctx.log.info("add them to the jadx inputs to decompile the hidden classes: {}", paths.joinToString(" "))
            return
        }
        val current = ctx.jadx.args.inputFiles.map { it.toPath().toAbsolutePath() }.toSet()
        val missing = paths.filter { it.toAbsolutePath() !in current }
        if (missing.isEmpty()) return
        gui.uiRun {
            runCatching {
                gui.mainFrame.javaClass.getMethod("addFiles", List::class.java).invoke(gui.mainFrame, missing)
            }.onFailure { ctx.log.warn("cannot add unpacked dex files to the project: {}", it.toString()) }
        }
    }

    private fun inputHash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (f in ctx.jadx.args.inputFiles.sortedBy { it.absolutePath }) {
            md.update(f.absolutePath.toByteArray())
            md.update(f.length().toString().toByteArray())
            md.update(f.lastModified().toString().toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
