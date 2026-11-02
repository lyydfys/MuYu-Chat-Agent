package com.muyuchat.mca.dsh

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class DshGgufToolStyleTest {

    @Test
    fun `hermes template selects the function-parameter form`() {
        val file = templateFile(
            "hermes",
            "{\"name\": \"bash\", \"arguments\": {}}\n" +
                "<tool_call>\n<function=example_function_name>\n" +
                "<parameter=example_parameter>value</parameter>\n</function>\n</tool_call>"
        )
        assertEquals(DshToolEnvelopeStyle.XML, DshGgufToolStyle.of(file.absolutePath))
    }

    @Test
    fun `qwen json template selects the native object form`() {
        val file = templateFile(
            "qwen-json",
            "For each function call, return a json object within <tool_call></tool_call> tags:\n" +
                "<tool_call>\n{\\\"name\\\": <function-name>, \\\"arguments\\\": <args>}\n</tool_call>"
        )
        assertEquals(DshToolEnvelopeStyle.QWEN_JSON, DshGgufToolStyle.of(file.absolutePath))
    }

    @Test
    fun `a tool block beyond the old four-kibibyte window is still classified`() {
        val file = templateFile(
            "long-template",
            "<tool_call>\n" + "template prose ".repeat(380) +
                "<function=bash>\n<parameter=command>pwd</parameter>\n" +
                "</function>\n</tool_call>"
        )
        assertEquals(DshToolEnvelopeStyle.XML, DshGgufToolStyle.of(file.absolutePath))
    }

    @Test
    fun `missing template falls back to the file-name hint`() {
        val hermes = File.createTempFile("Huihui-Qwen3.5-4B", ".gguf").apply {
            writeText("GGUF no template here")
            deleteOnExit()
        }
        val qwen = File.createTempFile("qwen-test", ".gguf").apply {
            writeText("GGUF no template here")
            deleteOnExit()
        }
        assertEquals(DshToolEnvelopeStyle.XML, DshGgufToolStyle.of(hermes.absolutePath))
        assertEquals(DshToolEnvelopeStyle.QWEN_JSON, DshGgufToolStyle.of(qwen.absolutePath))
    }

    private fun templateFile(name: String, template: String): File =
        File.createTempFile(name, ".gguf").apply {
            writeText("GGUF\u0000tokenizer.chat_template\u0008" + template)
            deleteOnExit()
        }
}
