package io.github.crockalet.haunt.protocol

import io.github.crockalet.haunt.core.HauntState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire form of [HauntState] with a short discriminator, e.g. `{"type":"Holding","fix":{…}}`, instead of
 * the fully-qualified class names kotlinx.serialization would use. JSON only.
 */
object HauntStateSerializer : KSerializer<HauntState> {
    const val IDLE = "Idle"
    const val HOLDING = "Holding"
    const val MOVING = "Moving"
    const val JOYSTICK = "Joystick"

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("HauntState")

    override fun serialize(encoder: Encoder, value: HauntState) {
        val json = (encoder as? JsonEncoder)?.json ?: throw SerializationException("HauntState is JSON-only")
        val (type, body) = when (value) {
            HauntState.Idle -> IDLE to JsonObject(emptyMap())
            is HauntState.Holding -> HOLDING to json.encodeToJsonElement(HauntState.Holding.serializer(), value)
            is HauntState.Moving -> MOVING to json.encodeToJsonElement(HauntState.Moving.serializer(), value)
            is HauntState.Joystick -> JOYSTICK to json.encodeToJsonElement(HauntState.Joystick.serializer(), value)
        }
        encoder.encodeJsonElement(JsonObject(mapOf("type" to JsonPrimitive(type)) + body.jsonObject))
    }

    override fun deserialize(decoder: Decoder): HauntState {
        val input = decoder as? JsonDecoder ?: throw SerializationException("HauntState is JSON-only")
        val obj = input.decodeJsonElement().jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: throw SerializationException("HauntState: missing \"type\"")
        val body = JsonObject(obj - "type")
        return when (type) {
            IDLE -> HauntState.Idle
            HOLDING -> input.json.decodeFromJsonElement(HauntState.Holding.serializer(), body)
            MOVING -> input.json.decodeFromJsonElement(HauntState.Moving.serializer(), body)
            JOYSTICK -> input.json.decodeFromJsonElement(HauntState.Joystick.serializer(), body)
            else -> throw SerializationException("HauntState: unknown type \"$type\"")
        }
    }
}

/** Short name of the state's mode: Idle, Holding, Moving or Joystick. */
val HauntState.typeName: String
    get() = when (this) {
        HauntState.Idle -> HauntStateSerializer.IDLE
        is HauntState.Holding -> HauntStateSerializer.HOLDING
        is HauntState.Moving -> HauntStateSerializer.MOVING
        is HauntState.Joystick -> HauntStateSerializer.JOYSTICK
    }
