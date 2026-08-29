# Non-Goals

TestInbox explicitly is **not**:

1. **A YOPmail/Mailinator-style consumer disposable-email website.** No
   public, unauthenticated inbox browsing; no product surface optimized for
   a human casually generating a throwaway address to dodge spam.
2. **An SMTP relay or outbound sending service.** TestInbox never sends mail
   on a user's behalf. It is inbound-only.
3. **Persistent email storage or an archival system.** Inboxes and messages
   are ephemeral by design (TTL-bounded); there is no "keep forever" tier in
   MVP, and the product should actively resist becoming one.
4. **An anonymous communication channel.** Inbox creation always requires an
   authenticated, scoped API key. Two parties cannot use TestInbox as a
   covert mail drop without an API key tied to a workspace.
5. **A general-purpose inbox/CRM/helpdesk product.** No reply, threading,
   labeling, or human-workflow features beyond debugging a received message.
6. **An assertion framework.** TestInbox provides matching/wait primitives
   and structured message data; it deliberately does not reimplement a full
   assertion library (e.g., no attempt to compete with AssertJ/Chai). SDK
   helpers stop at "give the test structured, easy-to-assert-on data."
7. **A drop-in replacement for a production transactional-email provider's
   analytics/deliverability tooling.** Bounce/complaint/deliverability
   analytics are out of scope; TestInbox is a *receiver* for testing, not a
   sending-side observability product.
