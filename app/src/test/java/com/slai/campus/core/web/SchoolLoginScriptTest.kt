package com.slai.campus.core.web

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.session.SchoolCredentials
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Test

class SchoolLoginScriptTest {
    @Test fun `generated scripts handle school two-step form and reject unsafe documents`() {
        val credentials = SchoolCredentials("test-only@example.invalid", "test-only'\\\n\"</script>")
        val harness = listOf(File("../tools/test-school-login.cjs"), File("tools/test-school-login.cjs"))
            .first { it.exists() }
        val process = ProcessBuilder("node", harness.absolutePath).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use {
            it.write(JsonObject(mapOf(
                "inspect" to JsonPrimitive(SchoolLoginScript.inspect()),
                "username" to JsonPrimitive(SchoolLoginScript.submit(credentials, false)),
                "password" to JsonPrimitive(SchoolLoginScript.submit(credentials, true)),
                "account" to JsonPrimitive(credentials.username), "secret" to JsonPrimitive(credentials.password)
            )).toString())
        }
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).isEqualTo(0)
        assertThat(process.inputStream.bufferedReader().readText()).contains("boundaries passed")
    }
}
