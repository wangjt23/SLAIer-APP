package com.slai.campus.core.web

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.slai.campus.core.session.SchoolCredentials
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Test

class SchoolLoginScriptTest {
    @Test fun `generated scripts handle school two-step form and reject unsafe documents`() {
        checkScripts("test-only@example.invalid")
    }

    @Test fun `student id is propagated through the school domain normalization`() {
        checkScripts("2026000000")
    }

    private fun checkScripts(account: String) {
        val credentials = SchoolCredentials(account, "test-only'\\\n\"</script>")
        val harness = listOf(File("../tools/test-school-login.cjs"), File("tools/test-school-login.cjs"))
            .first { it.exists() }
        // Drain output independently of waitFor: Linux pipes can fill with assertion diagnostics.
        val output = File.createTempFile("slaier-login-script-", ".log")
        val process = ProcessBuilder("node", harness.absolutePath)
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            process.outputStream.bufferedWriter().use {
            it.write(JsonObject(mapOf(
                "inspect" to JsonPrimitive(SchoolLoginScript.inspect()),
                "username" to JsonPrimitive(SchoolLoginScript.submit(credentials, false)),
                "password" to JsonPrimitive(SchoolLoginScript.submit(credentials, true)),
                "account" to JsonPrimitive(credentials.username), "secret" to JsonPrimitive(credentials.password)
            )).toString())
            }
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            val report = output.readText()
            assertWithMessage(report.take(4_000)).that(finished).isTrue()
            assertWithMessage(report.take(4_000)).that(process.exitValue()).isEqualTo(0)
            assertThat(report).contains("boundaries passed")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            output.delete()
        }
    }
}
