-- expect-exit: 1
-- expect: V23__fixture\.sql:4: \[data-movement\] CREATE TABLE … AS reads human_identity
CREATE TABLE contact_copy AS
SELECT subject, email FROM public.human_identity;
