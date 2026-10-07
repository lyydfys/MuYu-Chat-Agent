package com.muyuchat.api.local.adapter

import org.json.JSONObject

/**
 * Result of request validation and authentication mapping.
 */
sealed interface AuthValidationResult {
    data class Success(val token: String, val clientId: String) : AuthValidationResult
    data class Failure(val httpStatus: Int, val errorCode: String, val message: String) : AuthValidationResult
}

/**
 * Normalized API command metadata extracted from wire JSON.
 */
data class NormalizedApiRequest(
    val prompt: String,
    val model: String,
    val stream: Boolean,
    val temperature: Float,
    val maxTokens: Int,
    val idempotencyKey: String,
    val clientIp: String
)

/**
 * Validates request schema, protocol version, auth headers, and idempotency keys.
 * Hard rule: Authentication and schema failures are terminated at this gate,
 * never reaching the domain Facade or native engine layers.
 */
object LocalApiRequestMapper {

    private const val MIN_TOKEN_LENGTH = 8

    /**
     * Authenticates an incoming Authorization header.
     * Supports "Bearer <token>" format. If expectedToken is configured and non-empty,
     * header must match exactly.
     */
    fun validateAuth(
        authorizationHeader: String?,
        expectedToken: String? = null
    ): AuthValidationResult {
        if (expectedToken.isNullOrBlank()) {
            // Local loopback authentication disabled/permissive mode
            return AuthValidationResult.Success(token = "loopback-permissive", clientId = "localhost")
        }

        if (authorizationHeader.isNullOrBlank()) {
            return AuthValidationResult.Failure(
                httpStatus = 401,
                errorCode = "missing_authorization_header",
                message = "Missing Authorization header in request"
            )
        }

        val trimmed = authorizationHeader.trim()
        if (!trimmed.startsWith("Bearer ", ignoreCase = true)) {
            return AuthValidationResult.Failure(
                httpStatus = 401,
                errorCode = "invalid_auth_scheme",
                message = "Authorization header must use Bearer scheme"
            )
        }

        val suppliedToken = trimmed.substring(7).trim()
        if (suppliedToken.length < MIN_TOKEN_LENGTH || suppliedToken != expectedToken) {
            return AuthValidationResult.Failure(
                httpStatus = 403,
                errorCode = "invalid_credentials",
                message = "Supplied Bearer token is invalid or forbidden"
            )
        }

        return AuthValidationResult.Success(token = suppliedToken, clientId = "authenticated-client")
    }

    /**
     * Parses and normalizes incoming chat completion request JSON.
     * Rejects malformed payload or blank prompts.
     */
    fun mapChatCompletion(bodyJson: String, clientIp: String = "127.0.0.1"): Result<NormalizedApiRequest> = runCatching {
        require(bodyJson.isNotBlank()) { "Empty request body" }
        val json = JSONObject(bodyJson)

        val model = json.optString("model", "default-model")
        val stream = json.optBoolean("stream", false)
        val temp = json.optDouble("temperature", 0.7).toFloat()
        val maxTokens = json.optInt("max_tokens", 2048)
        val idempotency = json.optString("idempotency_key", "")

        // Support both single prompt and OpenAI messages array
        val prompt = when {
            json.has("prompt") -> json.getString("prompt")
            json.has("messages") -> {
                val messages = json.getJSONArray("messages")
                require(messages.length() > 0) { "Messages array must not be empty" }
                val lastMsg = messages.getJSONObject(messages.length() - 1)
                lastMsg.getString("content")
            }
            else -> throw IllegalArgumentException("Missing prompt or messages field")
        }

        require(prompt.isNotBlank()) { "Prompt content cannot be blank" }

        NormalizedApiRequest(
            prompt = prompt,
            model = model,
            stream = stream,
            temperature = temp.coerceIn(0.0f, 2.0f),
            maxTokens = maxTokens.coerceIn(1, 8192),
            idempotencyKey = idempotency,
            clientIp = clientIp
        )
    }
}
