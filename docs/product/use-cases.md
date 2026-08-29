# Primary Use Cases

## In MVP scope

1. **Signup email verification**: create inbox → trigger signup in SUT →
   `waitForMessage` matching sender/subject → extract verification link →
   continue the test flow.
2. **OTP / one-time-code verification**: same shape as above, extracting a
   code from text/HTML body rather than a link.
3. **Transactional email content assertions**: assert on subject, headers,
   sender, HTML/text content, and presence/metadata of attachments (e.g.,
   "an invoice PDF was attached").
4. **Local development debugging**: a developer runs TestInbox locally,
   points their app's SMTP config at the local adapter, and inspects
   received mail via the dashboard while iterating.

## Deferred (require capabilities cut from MVP)

5. **Webhook-triggered assertions** (push notification on message arrival,
   as an alternative to polling/waiting) — requires the `Webhook` entity.
6. **Custom/branded sender domains for the SUT's outbound mail to route
   through TestInbox** — requires customer-owned domain routing.
7. **Load/performance testing of an email-sending pipeline** — requires
   TestInbox to sustain and report on high-throughput inbound traffic; k6
   scenarios are part of the quality strategy but a dedicated product
   use case (e.g., "load-test my transactional email service") is not MVP.
8. **Cross-test-run correlation/reporting** (e.g., "show me every email
   received during CI run #482") — the motivating case for a `TestRun`
   entity; no concrete MVP use case requires it yet (see Human Decisions
   Required in `VISION.md`).
