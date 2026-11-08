package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.runtime.UNKNOWN
import jadx.plugins.emu.exec.runtime.UnknownVal

internal object OpaqueConstants {

    private const val MEASURED_STATE_MASK = -0x1000000
    private const val MEASURED_STATE_TOO_SMALL = 0x01000000
    private const val MODE_SHIFT = 30
    private const val MODE_MASK = 0x3 shl MODE_SHIFT

    fun register(hooks: Hooks): Int {
        var n = 0
        fun stub(cls: String, name: String, handler: (List<Any?>) -> Any?) {
            hooks.stub("$cls->$name") { replace(if (args.any { it is UnknownVal }) UNKNOWN else handler(args)) }
            n++
        }

        val view = "Landroid/view/View;"
        stub(view, "combineMeasuredStates") { a -> int(a, 0) or int(a, 1) }
        stub(view, "resolveSize") { a -> resolveSizeAndState(int(a, 0), int(a, 1), 0) and MEASURED_STATE_MASK.inv() }
        stub(view, "resolveSizeAndState") { a -> resolveSizeAndState(int(a, 0), int(a, 1), int(a, 2)) }
        stub(view, "getDefaultSize") { a ->
            val size = int(a, 0); val spec = int(a, 1)
            when (spec ushr MODE_SHIFT shl MODE_SHIFT) {
                MODE_MASK, 0x40000000 -> spec and MODE_MASK.inv()
                else -> size
            }
        }

        val spec = "Landroid/view/View\$MeasureSpec;"
        stub(spec, "makeMeasureSpec") { a -> (int(a, 0) and MODE_MASK.inv()) or (int(a, 1) and MODE_MASK) }
        stub(spec, "getMode") { a -> int(a, 0) and MODE_MASK }
        stub(spec, "getSize") { a -> int(a, 0) and MODE_MASK.inv() }

        val vc = "Landroid/view/ViewConfiguration;"
        val vcValues = mapOf(
            "getScrollBarSize" to 4, "getScrollBarFadeDuration" to 250, "getScrollDefaultDelay" to 300,
            "getFadingEdgeLength" to 12, "getPressedStateDuration" to 64, "getLongPressTimeout" to 400,
            "getKeyRepeatTimeout" to 400, "getKeyRepeatDelay" to 50, "getTapTimeout" to 100, "getJumpTapTimeout" to 500,
            "getDoubleTapTimeout" to 300, "getEdgeSlop" to 12, "getTouchSlop" to 8, "getWindowTouchSlop" to 16,
            "getMinimumFlingVelocity" to 50, "getMaximumFlingVelocity" to 8000, "getMaximumDrawingCacheSize" to 480 * 800 * 4,
        )
        for ((name, v) in vcValues) stub(vc, name) { _ -> v }
        stub(vc, "getZoomControlsTimeout") { _ -> 3000L }
        stub(vc, "getGlobalActionKeyTimeout") { _ -> 500L }

        val tu = "Landroid/text/TextUtils;"
        stub(tu, "isEmpty") { a -> val s = a.getOrNull(0); s == null || s.toString().isEmpty() }
        stub(tu, "indexOf") { a -> textIndexOf(a, last = false) }
        stub(tu, "lastIndexOf") { a -> textIndexOf(a, last = true) }
        stub(tu, "getOffsetBefore") { a -> val i = int(a, 1); if (i == 0) 0 else i - 1 }
        stub(tu, "getOffsetAfter") { a -> val s = str(a, 0) ?: return@stub UNKNOWN; val i = int(a, 1); if (i >= s.length) s.length else i + 1 }
        stub(tu, "getTrimmedLength") { a -> str(a, 0)?.trim()?.length ?: UNKNOWN }
        stub(tu, "getCapsMode") { a -> val s = str(a, 0) ?: return@stub UNKNOWN; if (s.isEmpty()) 0 else int(a, 2) and 0x1000 }
        stub(tu, "equals") { a -> a.getOrNull(0)?.toString() == a.getOrNull(1)?.toString() }

        val color = "Landroid/graphics/Color;"
        stub(color, "alpha") { a -> int(a, 0) ushr 24 }
        stub(color, "red") { a -> (int(a, 0) shr 16) and 0xff }
        stub(color, "green") { a -> (int(a, 0) shr 8) and 0xff }
        stub(color, "blue") { a -> int(a, 0) and 0xff }
        stub(color, "rgb") { a -> (0xff shl 24) or (int(a, 0) shl 16) or (int(a, 1) shl 8) or int(a, 2) }
        stub(color, "argb") { a -> (int(a, 0) shl 24) or (int(a, 1) shl 16) or (int(a, 2) shl 8) or int(a, 3) }

        val key = "Landroid/view/KeyEvent;"
        stub(key, "getDeadChar") { _ -> 0 }
        stub(key, "keyCodeFromString") { a -> val s = str(a, 0) ?: return@stub UNKNOWN; s.removePrefix("KEYCODE_").toIntOrNull() ?: 0 }
        stub(key, "getModifierMetaStateMask") { _ -> 0x33077 }
        stub(key, "normalizeMetaState") { a -> int(a, 0) and 0x33077.inv().inv() }

        stub("Landroid/view/Gravity;", "getAbsoluteGravity") { a ->
            val g = int(a, 0); val dir = int(a, 1)
            if (g and 0x800000 != 0) {
                var r = g
                if (r and 0x800003 == 0x800003) r = r xor 0x800003 or if (dir == 1) 5 else 3
                if (r and 0x800005 == 0x800005) r = r xor 0x800005 or if (dir == 1) 3 else 5
                r and 0x800000.inv()
            } else g
        }

        val elv = "Landroid/widget/ExpandableListView;"
        stub(elv, "getPackedPositionForGroup") { a -> int(a, 0).toLong() shl 32 }
        stub(elv, "getPackedPositionForChild") { a -> (1L shl 63) or (int(a, 0).toLong() shl 32) or (int(a, 1).toLong() and 0xffffffffL) }
        stub(elv, "getPackedPositionType") { a -> val p = long(a, 0); if (p == -1L) 2 else if (p and (1L shl 63) != 0L) 1 else 0 }
        stub(elv, "getPackedPositionGroup") { a -> val p = long(a, 0); if (p == -1L) -1 else ((p shr 32) and 0x7fffffffL).toInt() }
        stub(elv, "getPackedPositionChild") { a -> val p = long(a, 0); if (p == -1L || p and (1L shl 63) == 0L) -1 else (p and 0xffffffffL).toInt() }

        stub("Landroid/text/AndroidCharacter;", "getMirror") { a ->
            when (val c = int(a, 0).toChar()) {
                '(' -> ')'; ')' -> '('; '[' -> ']'; ']' -> '['; '{' -> '}'; '}' -> '{'; '<' -> '>'; '>' -> '<'
                else -> c
            }
        }

        stub("Landroid/telephony/cdma/CdmaCellLocation;", "convertQuartSecToDecDegrees") { a -> int(a, 0) / 14400.0 }

        val tv = "Landroid/util/TypedValue;"
        stub(tv, "complexToFloat") { a -> complexToFloat(int(a, 0)) }
        stub(tv, "complexToFraction") { a -> val f = complexToFloat(int(a, 0)); f * (if (int(a, 0) and 0xf == 0) float(a, 1) else float(a, 2)) }

        stub("Landroid/graphics/PointF;", "length") { a -> val x = float(a, 0); val y = float(a, 1); Math.sqrt((x * x + y * y).toDouble()).toFloat() }
        stub("Landroid/graphics/drawable/Drawable;", "resolveOpacity") { a ->
            val x = int(a, 0); val y = int(a, 1)
            when { x == y -> x; x == -3 || y == -3 -> -3; x == -1 || y == -1 -> -1; x == -2 || y == -2 -> -2; else -> -3 }
        }
        stub("Landroid/graphics/ImageFormat;", "getBitsPerPixel") { a ->
            when (int(a, 0)) { 4 -> 16; 16 -> 16; 17 -> 12; 20 -> 16; 0x32315659 -> 12; 0x23 -> 12; 0x2a -> 12; 0x20203859 -> 8; else -> -1 }
        }
        stub("Landroid/view/MotionEvent;", "axisFromString") { a -> str(a, 0)?.let { s -> AXES.indexOf(s.removePrefix("AXIS_")).takeIf { it >= 0 } ?: -1 } ?: UNKNOWN }

        val proc = "Landroid/os/Process;"
        stub(proc, "myPid") { _ -> 4242 }
        stub(proc, "myTid") { _ -> 4242 }
        stub(proc, "myUid") { _ -> 10042 }
        stub(proc, "getGidForName") { _ -> -1 }
        stub(proc, "getThreadPriority") { _ -> 0 }
        stub(proc, "getElapsedCpuTime") { _ -> 1234L }
        val clock = "Landroid/os/SystemClock;"
        stub(clock, "uptimeMillis") { _ -> 123456L }
        stub(clock, "elapsedRealtime") { _ -> 123456L }
        stub(clock, "elapsedRealtimeNanos") { _ -> 123456000000L }
        stub(clock, "currentThreadTimeMillis") { _ -> 1234L }
        stub(clock, "sleep") { _ -> null }
        val audio = "Landroid/media/AudioTrack;"
        stub(audio, "getMaxVolume") { _ -> 1.0f }
        stub(audio, "getMinVolume") { _ -> 0.0f }

        return n
    }

    private fun resolveSizeAndState(size: Int, spec: Int, childState: Int): Int {
        val mode = spec and MODE_MASK
        val specSize = spec and MODE_MASK.inv()
        val result = when (mode) {
            0x80000000.toInt() -> if (specSize < size) specSize or MEASURED_STATE_TOO_SMALL else size
            0x40000000 -> specSize
            else -> size
        }
        return result or (childState and MEASURED_STATE_MASK)
    }

    private fun complexToFloat(complex: Int): Float = (complex and -0x100) * RADIX_MULTS[(complex shr 4) and 3]

    private val RADIX_MULTS = floatArrayOf(1.0f / (1 shl 8), 1.0f / (1 shl 15), 1.0f / (1 shl 23), 1.0f / (1 shl 31))

    private val AXES = listOf(
        "X", "Y", "PRESSURE", "SIZE", "TOUCH_MAJOR", "TOUCH_MINOR", "TOOL_MAJOR", "TOOL_MINOR", "ORIENTATION",
        "VSCROLL", "HSCROLL", "Z", "RX", "RY", "RZ", "HAT_X", "HAT_Y", "LTRIGGER", "RTRIGGER", "THROTTLE", "RUDDER",
        "WHEEL", "GAS", "BRAKE", "DISTANCE", "TILT",
    )

    private fun textIndexOf(a: List<Any?>, last: Boolean): Any? {
        val s = str(a, 0) ?: return UNKNOWN
        return when (val needle = a.getOrNull(1)) {
            is Char, is Int -> {
                val c = if (needle is Char) needle else (needle as Int).toChar()
                when (a.size) {
                    2 -> if (last) s.lastIndexOf(c) else s.indexOf(c)
                    3 -> if (last) s.lastIndexOf(c, int(a, 2)) else s.indexOf(c, int(a, 2))
                    else -> {
                        val start = int(a, 2); val end = int(a, 3)
                        if (last) s.substring(0, minOf(end, s.length)).lastIndexOf(c, start) else s.substring(0, minOf(end, s.length)).indexOf(c, start)
                    }
                }
            }
            else -> {
                val sub = needle?.toString() ?: return UNKNOWN
                when (a.size) {
                    2 -> if (last) s.lastIndexOf(sub) else s.indexOf(sub)
                    3 -> if (last) s.lastIndexOf(sub, int(a, 2)) else s.indexOf(sub, int(a, 2))
                    else -> {
                        val start = int(a, 2); val end = int(a, 3)
                        if (last) s.substring(0, minOf(end, s.length)).lastIndexOf(sub, start) else s.substring(0, minOf(end, s.length)).indexOf(sub, start)
                    }
                }
            }
        }
    }

    private fun int(a: List<Any?>, i: Int): Int = when (val v = a.getOrNull(i)) {
        is Int -> v; is Long -> v.toInt(); is Char -> v.code; is Byte -> v.toInt(); is Short -> v.toInt(); is Boolean -> if (v) 1 else 0
        else -> 0
    }

    private fun long(a: List<Any?>, i: Int): Long = when (val v = a.getOrNull(i)) { is Long -> v; else -> int(a, i).toLong() }

    private fun float(a: List<Any?>, i: Int): Float = when (val v = a.getOrNull(i)) {
        is Float -> v; is Int -> Float.fromBits(v); is Double -> v.toFloat(); is Long -> v.toFloat(); else -> 0f
    }

    private fun str(a: List<Any?>, i: Int): String? = when (val v = a.getOrNull(i)) { is String -> v; is UnknownVal, null -> null; else -> v.toString() }
}
