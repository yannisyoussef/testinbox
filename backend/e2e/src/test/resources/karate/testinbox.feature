Feature: TestInbox v1 REST contract acceptance

  Background:
    * url baseUrl
    * def authHeader = 'Bearer ' + apiKey
    * configure headers = { Authorization: '#(authHeader)' }

  Scenario: unauthenticated requests receive an RFC 7807 401
    * configure headers = {}
    Given path 'v1', 'inboxes'
    And request {}
    When method post
    Then status 401
    And match header Content-Type contains 'application/problem+json'
    And match response.type == 'https://testinbox.email/problems/unauthorized'
    And match response.correlationId == '#string'

  Scenario: create, fetch, list, wait-timeout, delete lifecycle
    Given path 'v1', 'inboxes'
    And request { aliasHint: 'karate', ttlSeconds: 300 }
    When method post
    Then status 201
    And match response.addressMode == 'GENERATED'
    And match response.state == 'ACTIVE'
    And match response.address == '#regex karate-.+@.+'
    * def inboxId = response.id

    Given path 'v1', 'inboxes', inboxId
    When method get
    Then status 200
    And match response.id == inboxId

    Given path 'v1', 'inboxes', inboxId, 'messages'
    When method get
    Then status 200
    And match response.items == '#[0]'

    # ADR-020: wait-window expiry is 200 + TIMEOUT with diagnostics, never 408.
    Given path 'v1', 'inboxes', inboxId, 'messages', 'wait'
    And request { matcher: { subjectContains: 'never' }, timeoutSeconds: 1 }
    When method post
    Then status 200
    And match response.status == 'TIMEOUT'
    And match response.elapsedMs == '#number'
    And match response.arrivedButUnmatchedCount == '#number'
    And match response.parseFailedCount == '#number'

    Given path 'v1', 'inboxes', inboxId
    When method delete
    Then status 204

    # Waiting on a non-active inbox: 410 while the row survives, 404 once swept.
    Given path 'v1', 'inboxes', inboxId, 'messages', 'wait'
    And request { timeoutSeconds: 1 }
    When method post
    Then assert responseStatus == 410 || responseStatus == 404

  Scenario: exact address reservation conflict yields a 409 problem
    * def localPart = 'karate-' + java.util.UUID.randomUUID().toString().substring(0, 8)
    Given path 'v1', 'inboxes'
    And request { addressMode: 'EXACT', localPart: '#(localPart)' }
    When method post
    Then status 201
    And match response.address == localPart + '@' + mailDomain

    Given path 'v1', 'inboxes'
    And request { addressMode: 'EXACT', localPart: '#(localPart)' }
    When method post
    Then status 409
    And match header Content-Type contains 'application/problem+json'
    And match response.type == 'https://testinbox.email/problems/address-already-reserved'

  Scenario: unknown resources are 404 problems
    Given path 'v1', 'inboxes', '00000000-0000-0000-0000-00000000dead'
    When method get
    Then status 404
    And match response.type == 'https://testinbox.email/problems/inbox-not-found'

    Given path 'v1', 'messages', '00000000-0000-0000-0000-00000000dead'
    When method get
    Then status 404
