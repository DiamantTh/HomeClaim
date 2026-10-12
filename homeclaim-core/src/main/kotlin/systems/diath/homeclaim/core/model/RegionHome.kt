package systems.diath.homeclaim.core.model

/**
 * A typed plot-home location. World and area are intentionally not duplicated here:
 * they are resolved through the owning [Region.areaId].
 */
data class RegionHome(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float
) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite() &&
        yaw.isFinite() && pitch.isFinite()
}
