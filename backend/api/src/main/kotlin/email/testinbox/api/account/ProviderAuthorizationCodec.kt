package email.testinbox.api.account

import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import tools.jackson.databind.ObjectMapper

/** Explicit bounded JSON fields; never Java serialization or a polymorphic object mapper. */
class ProviderAuthorizationCodec(
    private val mapper: ObjectMapper,
) {
    fun encode(request: OAuth2AuthorizationRequest): String =
        mapper.writeValueAsString(
            mapOf(
                "state" to request.state,
                "clientId" to request.clientId,
                "redirectUri" to request.redirectUri,
                "scopes" to request.scopes,
                "attributes" to request.attributes,
                "parameters" to request.additionalParameters,
            ),
        )

    fun decode(
        raw: String,
        state: String,
        registration: ClientRegistration,
    ): OAuth2AuthorizationRequest? =
        runCatching {
            val node = mapper.readTree(raw)
            require(node["state"].asText() == state)
            require(node["clientId"].asText() == registration.clientId)
            require(node["redirectUri"].asText() == registration.redirectUri)
            val scopes = node["scopes"].values().map { it.asText() }.toSet()
            require(scopes == registration.scopes)
            val attributes = node["attributes"].properties().associate { it.key to it.value.asText() }
            val parameters = node["parameters"].properties().associate { it.key to it.value.asText() }
            require(attributes["registration_id"] == registration.registrationId)
            require(!attributes["code_verifier"].isNullOrBlank() && parameters["code_challenge_method"] == "S256")
            if (registration.registrationId == "google") {
                require(!attributes["nonce"].isNullOrBlank() && !parameters["nonce"].isNullOrBlank())
            }
            OAuth2AuthorizationRequest
                .authorizationCode()
                .authorizationUri(requireNotNull(registration.providerDetails.authorizationUri))
                .clientId(registration.clientId)
                .redirectUri(registration.redirectUri)
                .scopes(scopes)
                .state(state)
                .attributes { it.putAll(attributes) }
                .additionalParameters { it.putAll(parameters) }
                .build()
        }.getOrNull()
}
