package io.github.crockalet.haunt.cli

import com.github.ajalt.clikt.command.main

suspend fun main(args: Array<String>) {
    quietLibraryLogging()
    HauntCli().main(escapeNegativeNumbers(args.toList()))
}

/**
 * The MCP SDK logs through kotlin-logging/SLF4J. Keep stdout clean (it is the MCP channel) and avoid
 * SLF4J's "no providers" warning; users can still override these with -D flags via JAVA_OPTS.
 */
internal fun quietLibraryLogging() {
    mapOf(
        "kotlin-logging.logStartupMessage" to "false",
        "slf4j.provider" to "org.slf4j.helpers.NOP_FallbackServiceProvider",
        "slf4j.internal.verbosity" to "WARN",
    ).forEach { (key, value) -> if (System.getProperty(key) == null) System.setProperty(key, value) }
}
