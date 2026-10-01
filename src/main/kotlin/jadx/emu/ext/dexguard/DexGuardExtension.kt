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
        val stubs = ctx.emu.engine.android
        val hooks = ctx.emu.world.hooks
        val app = AppInfo(ctx.jadx.decompiler)
        OpaqueConstants.register(stubs)
        HostShims(app).register(stubs, hooks)
        AppContextStubs(app).register(stubs, hooks)
        val unpacker = Unpacker(ctx, hooks)
        val streamVm by lazy { ctx.emu.engine.newVm(unpacker.limits, hooks = hooks) }
        DexStreams(ctx.emu.source) { streamVm }.register(hooks)
        HiddenDex(ctx.emu.source, ctx.emu.engine, hooks, unpacker.limits, ctx.log, unpacker::onLoad).register()
        unpacker.registerActions()
    }
}
