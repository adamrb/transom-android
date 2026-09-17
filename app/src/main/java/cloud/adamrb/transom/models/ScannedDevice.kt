package cloud.adamrb.transom.models

data class ScannedDevice(
    val name: String,
    val serialNumber: String,
    val rssi: Int
)
