-- expect-exit: 1
-- expect: V23__fixture\.sql:5: \[grant\] GRANT to testinbox_ingestion
-- expect: V23__fixture\.sql:6: \[grant\] GRANT to testinbox_ingestion
-- expect: V23__fixture\.sql:7: \[grant\] GRANT to testinbox_ingestion
GRANT SELECT ON inbox_note TO testinbox_ingestion;
GRANT SELECT, INSERT ON inbox_note TO testinbox_api, "testinbox_ingestion" WITH GRANT OPTION;
GRANT testinbox_api TO testinbox_ingestion;
