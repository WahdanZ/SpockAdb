package spock.adb.mcp.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/*
 * The numeric argument accessors, beside the string and flag ones in Schema.kt. Each takes the
 * range its schema declares and refuses a value outside it, so what a client is told and what a
 * tool accepts are one value.
 */

/**
 * A required whole-number argument within [bounds], the range its schema declares through
 * [Schema.ObjectBuilder.integer].
 *
 * Lived in InteractionTools while tap and swipe were the only tools taking coordinates;
 * moved next to the other accessors once the proxy tools needed a port.
 *
 * @throws IllegalArgumentException when absent, when the value is not a whole number (see
 *   [asStrictInt]), or when it is outside [bounds].
 */
fun JsonObject.requiredInt(name: String, bounds: IntRange): Int {
    val element = get(name)?.takeIf { !it.isJsonNull }
        ?: throw IllegalArgumentException("Missing required argument '$name'")
    return element.asStrictInt(name, bounds)
}

/**
 * An optional whole-number argument with a default. A value that is present is held to the
 * same rules as [requiredInt] — sending one that is wrong is not the same as leaving it out.
 */
fun JsonObject.optionalInt(name: String, default: Int, bounds: IntRange): Int =
    optionalInt(name, bounds) ?: default

/** As [optionalInt], but null when omitted, for a tool whose fallback is not a constant. */
fun JsonObject.optionalInt(name: String, bounds: IntRange): Int? =
    get(name)?.takeIf { !it.isJsonNull }?.asStrictInt(name, bounds)

/**
 * An optional number that may have a fraction, within [bounds], or [default] when omitted.
 *
 * As strict as [optionalInt] is about a whole number: `"0.5"` sent as a string is refused
 * rather than parsed.
 */
fun JsonObject.optionalNumber(name: String, default: Double, bounds: ClosedFloatingPointRange<Double>): Double {
    val element = get(name)?.takeIf { !it.isJsonNull } ?: return default
    require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
        "Argument '$name' must be a number, got $element."
    }
    val value = element.asDouble
    require(value in bounds) {
        "Argument '$name' must be between ${bounds.start} and ${bounds.endInclusive}, got ${element.asString}."
    }
    return value
}

/**
 * The value as an Int within [bounds], or an error naming [name] and what is wrong with it.
 *
 * Gson's `asInt` truncates, so a port of `8888.9` quietly became 8888 and `"8888"` was parsed
 * out of a string, neither of which an agent that sent them would have meant. A number with a
 * zero fractional part such as `8888.0` is accepted, because JSON Schema's `integer` accepts
 * it too; one with a real fraction, or outside Int's range, is refused. Read as a BigDecimal
 * rather than a Double so a large value is not rounded or clamped into something that looks
 * valid before it is checked.
 *
 * This is also the one range check behind every whole-number argument. A value outside [bounds]
 * is refused, never clamped: `maxLines: -5` used to be read as 1 and answered "Logcat returned
 * nothing.", which told the agent nothing about the mistake it had made.
 */
private fun JsonElement.asStrictInt(name: String, bounds: IntRange): Int {
    require(isJsonPrimitive) {
        "Argument '$name' must be a whole number, not ${if (isJsonArray) "an array" else "an object"}."
    }
    val primitive = asJsonPrimitive
    require(primitive.isNumber) { "Argument '$name' must be a whole number, got $primitive." }

    val number = primitive.asBigDecimal.stripTrailingZeros()
    require(number.scale() <= 0) {
        "Argument '$name' must be a whole number, got ${primitive.asString}."
    }
    val value = try {
        number.intValueExact()
    } catch (e: ArithmeticException) {
        throw IllegalArgumentException("Argument '$name' is out of range: ${primitive.asString}.", e)
    }
    require(value in bounds) { "Argument '$name' must be between ${bounds.first} and ${bounds.last}, got $value." }
    return value
}
