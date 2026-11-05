package me.weishu.kernelsu.data.system

import kotlinx.serialization.Serializable

@Serializable
data class ManagerAppearance(val displayName: String, val iconEntry: String? = null) {
    init {
        require(displayName == validateName(displayName))
        require(iconEntry == null || (iconEntry.startsWith("res/") &&
            iconEntry.split('/').none { it == ".." || it.isEmpty() } && !iconEntry.contains('\\')))
    }

    companion object {
        fun validateName(value: String): String {
            val name = value.trim()
            require(name.isNotEmpty() && name.codePointCount(0, name.length) <= 40)
            require(name.codePoints().allMatch {
                !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() &&
                    Character.getType(it) != Character.SURROGATE.toInt()
            })
            return name
        }
    }
}
