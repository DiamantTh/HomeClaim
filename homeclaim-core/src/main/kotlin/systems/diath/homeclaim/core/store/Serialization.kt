package systems.diath.homeclaim.core.store

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.module.SimpleModule
import systems.diath.homeclaim.core.model.ComponentConfig
import systems.diath.homeclaim.core.model.ElevatorConfig
import systems.diath.homeclaim.core.model.PolicyValue
import systems.diath.homeclaim.core.model.TeleportConfig
import java.sql.PreparedStatement
import java.sql.Types
import java.sql.ResultSet
import java.util.UUID

object Serialization {
    val mapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .registerModule(
            SimpleModule()
                .addDeserializer(PolicyValue::class.java, PolicyValueDeserializer())
                .addDeserializer(ComponentConfig::class.java, ComponentConfigDeserializer())
        )
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun toJson(value: Any): String = mapper.writeValueAsString(value)

    inline fun <reified T> fromJson(json: String): T = mapper.readValue(json)

    fun ResultSet.getJson(column: String): String = getString(column)

    /** Reads the pre-release JSON shape emitted for the sealed PolicyValue variants. */
    private class PolicyValueDeserializer : JsonDeserializer<PolicyValue>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): PolicyValue {
            val value: JsonNode = parser.codec.readTree(parser)
            if (!value.isObject) throw JsonMappingException.from(parser, "PolicyValue must be a JSON object")

            val allowed = value.get("allowed")
            if (allowed != null && allowed.isBoolean) return PolicyValue.Bool(allowed.booleanValue())

            val wrapped = value.get("value")
                ?: throw JsonMappingException.from(parser, "PolicyValue must contain 'allowed' or 'value'")
            return when {
                wrapped.isIntegralNumber && wrapped.canConvertToInt() -> PolicyValue.IntValue(wrapped.intValue())
                wrapped.isTextual -> PolicyValue.Text(wrapped.textValue())
                else -> throw JsonMappingException.from(parser, "Unsupported PolicyValue JSON value")
            }
        }
    }

    /** Infers the concrete component config from the stable fields already persisted in JSON. */
    private class ComponentConfigDeserializer : JsonDeserializer<ComponentConfig>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): ComponentConfig {
            val value: JsonNode = parser.codec.readTree(parser)
            return when {
                value.has("linkId") -> parser.codec.treeToValue(value, TeleportConfig::class.java)
                value.has("mode") || value.has("rangeBlocks") || value.has("searchRule") ->
                    parser.codec.treeToValue(value, ElevatorConfig::class.java)
                else -> throw JsonMappingException.from(parser, "Unknown component config shape")
            }
        }
    }
}

/** Reads UUID columns through their portable textual JDBC representation. */
fun ResultSet.getUuid(column: String): UUID? = getString(column)?.let(UUID::fromString)

fun ResultSet.getRequiredUuid(column: String): UUID =
    getUuid(column) ?: throw IllegalStateException("Required UUID column '$column' was null")

/** Binds serialized JSON using PostgreSQL's non-string parameter type, portable text elsewhere. */
fun PreparedStatement.setJsonString(parameterIndex: Int, json: String) {
    if (connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)) {
        setObject(parameterIndex, json, Types.OTHER)
    } else {
        setString(parameterIndex, json)
    }
}
