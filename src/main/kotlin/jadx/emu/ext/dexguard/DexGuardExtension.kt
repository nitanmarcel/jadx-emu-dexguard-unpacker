package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.EmuExtension
import jadx.plugins.emu.api.EmuExtensionContext
import jadx.plugins.emu.api.EmuExtensionInfo
import jadx.plugins.emu.api.ExtensionMode

class DexGuardExtension : EmuExtension {

    override fun info(): EmuExtensionInfo = EmuExtensionInfo(
        id = "dexguard-unpacker",
        name = "DexGuard unpacker",
        description = "Unpacks the dex files DexGuard loads at runtime",
        mode = ExtensionMode.ON_DEMAND,
        requiredEmuVersion = "0.1.0",
    )

    override fun init(ctx: EmuExtensionContext) {
        val hooks = ctx.hooks
        val app = AppInfo(ctx.jadx.decompiler)
        OpaqueConstants.register(hooks)
        HostShims(app).register(hooks)
        AppContextStubs(app).register(hooks)
        val unpacker = Unpacker(ctx)
        val streamVm by lazy { ctx.emu.newVm(unpacker.limits) }
        DexStreams(ctx.emu.source) { streamVm }.register(hooks)
        HiddenDex(ctx.emu, hooks, unpacker.limits, ctx.log, unpacker::onLoad).register()
        unpacker.registerActions()
    }
}
