package email.testinbox.api.account

import email.testinbox.domain.identity.ContactEmail
import email.testinbox.domain.identity.LoginProvider
import email.testinbox.domain.identity.VerifiedLoginIdentity
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationProvider
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestTemplate
import java.net.http.HttpClient
import java.time.Duration

/** SDK protocol verification only. No SecurityContext, authorized-client store or HTTP session. */
class ProviderProtocols(
    val registrations: ClientRegistrationRepository,
) {
    val resolver =
        DefaultOAuth2AuthorizationRequestResolver(registrations, "/account/oauth/authorize").apply {
            setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
        }
    private val transport =
        JdkClientHttpRequestFactory(
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
        ).apply { setReadTimeout(Duration.ofSeconds(5)) }
    private val rest = RestTemplate(transport)
    private val tokens =
        RestClientAuthorizationCodeTokenResponseClient().apply {
            setRestClient(
                RestClient
                    .builder()
                    .requestFactory(transport)
                    .configureMessageConverters { converters ->
                        converters.addCustomConverter(FormHttpMessageConverter())
                        converters.addCustomConverter(OAuth2AccessTokenResponseHttpMessageConverter())
                    }.defaultStatusHandler(OAuth2ErrorResponseErrorHandler())
                    .build(),
            )
        }
    private val github = DefaultOAuth2UserService().apply { setRestOperations(rest) }
    private val oidc = OidcUserService().apply { setRetrieveUserInfo { false } }
    private val manager =
        ProviderManager(
            OidcAuthorizationCodeAuthenticationProvider(tokens, oidc).apply {
                // Same SDK claim conversion/validators, with bounded JWK transport and RS256 only.
                setJwtDecoderFactory { registration ->
                    NimbusJwtDecoder
                        .withJwkSetUri(requireNotNull(registration.providerDetails.jwkSetUri))
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .restOperations(rest)
                        .build()
                        .apply {
                            setJwtValidator(JwtValidators.createDefaultWithValidators(OidcIdTokenValidator(registration)))
                            setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter())
                        }
                }
            },
            OAuth2LoginAuthenticationProvider(tokens, OAuth2UserService<OAuth2UserRequest, OAuth2User> { request -> githubUser(request) }),
        )

    fun authenticate(
        registration: ClientRegistration,
        authorization: OAuth2AuthorizationRequest,
        response: OAuth2AuthorizationResponse,
    ): VerifiedLoginIdentity {
        val result =
            manager.authenticate(
                OAuth2LoginAuthenticationToken(registration, OAuth2AuthorizationExchange(authorization, response)),
            )
        val user = result.principal as OAuth2User
        return if (registration.registrationId == "google") googleIdentity(user as OidcUser) else githubIdentity(user)
    }

    private fun githubUser(request: OAuth2UserRequest): OAuth2User {
        val user = github.loadUser(request)
        val endpoint = request.clientRegistration.providerDetails.userInfoEndpoint.uri + "/emails?per_page=100"
        val emails =
            RestClient
                .create(rest)
                .get()
                .uri(endpoint)
                .headers { it.setBearerAuth(request.accessToken.tokenValue) }
                .retrieve()
                .body(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
                ?: rejected()
        val primary = emails.singleOrNull { it["primary"] == true && it["verified"] == true } ?: rejected()
        val email = ContactEmail.parse(primary["email"] as? String ?: rejected()) ?: rejected()
        val attributes = user.attributes + (VERIFIED_EMAIL to email.value)
        return DefaultOAuth2User(user.authorities, attributes, "id")
    }

    private fun googleIdentity(user: OidcUser): VerifiedLoginIdentity {
        // Use signed ID-token claims, never an independently supplied profile's email.
        val claims = user.idToken.claims
        val email = ContactEmail.parse(claims["email"] as? String ?: rejected()) ?: rejected()
        val domain = email.value.substringAfter('@')
        val hosted = claims["hd"] as? String
        if (claims["email_verified"] != true) rejected()
        val gmail = domain in setOf("gmail.com", "googlemail.com")
        val workspace = !hosted.isNullOrBlank() && domain.equals(hosted, ignoreCase = true)
        if (!gmail && !workspace) rejected()
        return VerifiedLoginIdentity(LoginProvider.GOOGLE, "https://accounts.google.com", requireNotNull(user.idToken.subject), email)
    }

    private fun githubIdentity(user: OAuth2User): VerifiedLoginIdentity {
        val id = user.attributes["id"] as? Number ?: rejected()
        val subject = id.toString().takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) } ?: rejected()
        val email = ContactEmail.parse(user.attributes[VERIFIED_EMAIL] as? String ?: rejected()) ?: rejected()
        return VerifiedLoginIdentity(LoginProvider.GITHUB, "https://github.com", subject, email)
    }

    companion object {
        private const val VERIFIED_EMAIL = "testinbox_verified_primary_email"

        private fun rejected(): Nothing = throw OAuth2AuthenticationException(OAuth2Error("unverified-contact"))
    }
}
