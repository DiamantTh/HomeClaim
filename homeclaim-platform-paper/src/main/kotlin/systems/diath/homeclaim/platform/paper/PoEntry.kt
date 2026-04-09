package systems.diath.homeclaim.platform.paper

data class PoEntry(
    val singular: String,
    val plural: String? = null,
    val translations: List<String> = emptyList()
)
