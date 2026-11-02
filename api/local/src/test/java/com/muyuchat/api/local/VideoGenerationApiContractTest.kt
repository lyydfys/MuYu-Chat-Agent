package com.muyuchat.api.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `POST /v1/video` request contract.
 *
 * The engine field is the whole point of the endpoint: an omitted/empty value must keep the
 * built-in MCA engine, an explicit `sdcpp_external` must reach the provider verbatim, and any
 * other value must be a 400 instead of a silent fallback (a typo must never quietly run the
 * default engine).
 */
class VideoGenerationApiContractTest {

    private fun body(
        engine: String? = null,
        extra: Map<String, Any?> = emptyMap(),
        overrides: Map<String, Any?> = emptyMap(),
    ): String {
        val fields = linkedMapOf<String, Any?>(
            "prompt" to "a red apple on a wooden table",
            "width" to 256,
            "height" to 256,
            "frames" to 5,
            "fps" to 24,
            "steps" to 1,
            "seed" to 1234,
        )
        if (engine != null) fields["engine"] = engine
        fields.putAll(overrides)
        fields.putAll(extra)
        return fields.entries.joinToString(",", "{", "}") { (key, value) ->
            val rendered = when (value) {
                null -> "null"
                is String -> "\"$value\""
                is Boolean, is Number -> value.toString()
                else -> "\"$value\""
            }
            "\"$key\":$rendered"
        }
    }

    @Test
    fun `an omitted engine keeps the built-in pipeline`() {
        val request = VideoGenerationApiContract.parseRequest(body())
        assertEquals("", request.engine)
        assertEquals("a red apple on a wooden table", request.prompt)
        assertEquals(256L, request.width)
        assertEquals(5L, request.frames)
        assertEquals(24L, request.fps)
        assertEquals(1L, request.steps)
        assertEquals(1234L, request.seed)
        assertNull(request.ditPath)
        assertNull(request.vaePath)
        assertNull(request.tePath)
    }

    @Test
    fun `an explicit external engine is accepted verbatim`() {
        val request = VideoGenerationApiContract.parseRequest(body(engine = "sdcpp_external"))
        assertEquals("sdcpp_external", request.engine)
    }

    @Test
    fun `an empty engine string means the built-in pipeline`() {
        assertEquals("", VideoGenerationApiContract.parseRequest(body(engine = "")).engine)
    }

    @Test
    fun `an unknown engine is rejected instead of falling back`() {
        for (value in listOf("SDCPP_EXTERNAL", "external", "sdcpp-external", "builtin", "default")) {
            val failure = runCatching { VideoGenerationApiContract.parseRequest(body(engine = value)) }
                .exceptionOrNull()
            assertNotNull("engine '$value' must be rejected", failure)
            assertEquals(
                "engine '$value' must use the dedicated code",
                "unsupported_video_engine",
                (failure as VideoGenerationContractException).code,
            )
            assertEquals(400, failure.httpStatus)
        }
    }

    @Test
    fun `an unknown field is rejected instead of ignored`() {
        val failure = runCatching {
            VideoGenerationApiContract.parseRequest(body(extra = mapOf("loraz" to "[]")))
        }.exceptionOrNull() as VideoGenerationContractException
        assertEquals("unsupported_parameter", failure.code)
        assertEquals(400, failure.httpStatus)
    }

    /** Raw JSON so `loras` can be a real array; [body] would quote it as a string. */
    private fun bodyWithLoras(engine: String, lorasJson: String): String =
        "{\"prompt\":\"a red apple\",\"width\":256,\"height\":256,\"frames\":5,\"fps\":24," +
            "\"steps\":1,\"seed\":1234,\"engine\":\"$engine\",\"loras\":$lorasJson}"

    @Test
    fun `loras is accepted only with the external engine`() {
        val request = VideoGenerationApiContract.parseRequest(
            bodyWithLoras("sdcpp_external", """[{"path":"/models/turbo.safetensors","weight":1.0}]"""),
        )
        assertEquals(1, request.loraSpecs.size)
        assertEquals("/models/turbo.safetensors", request.loraSpecs.single().path)
        assertEquals(1.0, request.loraSpecs.single().weight, 0.0)

        val failure = runCatching {
            VideoGenerationApiContract.parseRequest(
                bodyWithLoras("", """[{"path":"/models/turbo.safetensors","weight":1.0}]"""),
            )
        }.exceptionOrNull() as VideoGenerationContractException
        assertEquals("loras_requires_external_engine", failure.code)
    }

    @Test
    fun `a malformed loras entry is rejected with its own code`() {
        val cases = listOf(
            """[{"path":"","weight":1.0}]""",
            """[{"path":"/m/a.safetensors"}]""",
            """[{"path":"/m/a.safetensors","weight":0}]""",
            """[{"path":"/m/a.safetensors","weight":9}]""",
            """{"path":"/m/a.safetensors","weight":1.0}""",
        )
        for (value in cases) {
            val failure = runCatching {
                VideoGenerationApiContract.parseRequest(bodyWithLoras("sdcpp_external", value))
            }.exceptionOrNull() as VideoGenerationContractException
            assertEquals("invalid_loras for $value", "invalid_loras", failure.code)
        }
    }

    @Test
    fun `wan frame alignment and geometry rules are enforced`() {
        val frames = runCatching {
            VideoGenerationApiContract.parseRequest(body(overrides = mapOf("frames" to 8)))
        }.exceptionOrNull() as VideoGenerationContractException
        assertEquals("invalid_frames", frames.code)
        assertEquals(422, frames.httpStatus)

        val size = runCatching {
            VideoGenerationApiContract.parseRequest(body(overrides = mapOf("width" to 250)))
        }.exceptionOrNull() as VideoGenerationContractException
        assertEquals("invalid_size", size.code)

        val steps = runCatching {
            VideoGenerationApiContract.parseRequest(body(overrides = mapOf("steps" to 0)))
        }.exceptionOrNull() as VideoGenerationContractException
        assertEquals("invalid_steps", steps.code)
    }

    @Test
    fun `prompt is required and bounded`() {
        val missing = runCatching {
            VideoGenerationApiContract.parseRequest(body(overrides = mapOf("prompt" to "  ")))
        }.exceptionOrNull() as VideoGenerationContractException
        assertEquals("invalid_prompt", missing.code)

        val tooLong = runCatching {
            VideoGenerationApiContract.parseRequest(
                body(overrides = mapOf("prompt" to "x".repeat(VideoGenerationApiContract.MAX_PROMPT_CHARS + 1))),
            )
        }.exceptionOrNull() as VideoGenerationContractException
        assertEquals("prompt_too_long", tooLong.code)
        assertEquals(422, tooLong.httpStatus)
    }

    @Test
    fun `explicit component paths are optional and must be strings`() {
        val withPaths = VideoGenerationApiContract.parseRequest(
            body(
                extra = mapOf(
                    "ditPath" to "/data/dit.gguf",
                    "vaePath" to "/data/vae.safetensors",
                    "tePath" to "/data/te.gguf",
                ),
            ),
        )
        assertEquals("/data/dit.gguf", withPaths.ditPath)
        assertEquals("/data/vae.safetensors", withPaths.vaePath)
        assertEquals("/data/te.gguf", withPaths.tePath)

        val blank = runCatching {
            VideoGenerationApiContract.parseRequest(body(extra = mapOf("ditPath" to " ")))
        }.exceptionOrNull() as VideoGenerationContractException
        assertEquals("invalid_request", blank.code)
    }

    @Test
    fun `a malformed body is a 400`() {
        val failure = runCatching {
            VideoGenerationApiContract.parseRequest("{not json")
        }.exceptionOrNull() as VideoGenerationContractException
        assertEquals("invalid_json", failure.code)
        assertEquals(400, failure.httpStatus)
    }

    @Test
    fun `backend defaults to htp for the external engine and accepts cpu`() {
        assertEquals(
            "htp",
            VideoGenerationApiContract.parseRequest(body(engine = "sdcpp_external")).backend,
        )
        assertEquals(
            "cpu",
            VideoGenerationApiContract.parseRequest(
                body(engine = "sdcpp_external", extra = mapOf("backend" to "cpu")),
            ).backend,
        )
    }

    @Test
    fun `an invalid backend is rejected instead of falling back`() {
        for (value in listOf("gpu", "HTP", "npu", "")) {
            val failure = runCatching {
                VideoGenerationApiContract.parseRequest(
                    body(engine = "sdcpp_external", extra = mapOf("backend" to value)),
                )
            }.exceptionOrNull() as VideoGenerationContractException
            assertEquals("backend '$value' must be rejected", "unsupported_video_backend", failure.code)
            assertEquals(400, failure.httpStatus)
        }
    }

    @Test
    fun `a backend without the external engine is rejected`() {
        val failure = runCatching {
            VideoGenerationApiContract.parseRequest(body(extra = mapOf("backend" to "cpu")))
        }.exceptionOrNull() as VideoGenerationContractException
        assertEquals("backend_requires_external_engine", failure.code)
        assertEquals(400, failure.httpStatus)
    }
}
