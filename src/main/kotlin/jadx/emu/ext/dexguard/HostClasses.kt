package jadx.emu.ext.dexguard

internal object HostClasses {
    fun of(desc: String): Class<*> = when (desc) {
        "I" -> Integer.TYPE; "J" -> java.lang.Long.TYPE; "Z" -> java.lang.Boolean.TYPE; "B" -> java.lang.Byte.TYPE
        "C" -> Character.TYPE; "S" -> java.lang.Short.TYPE; "F" -> java.lang.Float.TYPE; "D" -> java.lang.Double.TYPE
        "V" -> Void.TYPE
        else -> if (desc.startsWith("[")) java.lang.reflect.Array.newInstance(of(desc.substring(1)), 0).javaClass
        else Class.forName(desc.removePrefix("L").removeSuffix(";").replace('/', '.'))
    }
}
