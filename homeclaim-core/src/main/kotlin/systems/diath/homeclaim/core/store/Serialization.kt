package systems.diath.homeclaim.core.store

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import systems.diath.homeclaim.core.model.PolicyValue
import java.sql.ResultSet

object Serialization {
    val mapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun toJson(value: Any): String = mapper.writeValueAsString(value)

    inline fun <reified T> fromJson(json: String): T = mapper.readValue(json)

    fun ResultSet.getJson(column: String): String = getString(column)
}
