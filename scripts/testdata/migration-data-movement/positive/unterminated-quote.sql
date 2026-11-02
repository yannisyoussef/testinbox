-- expect-exit: 1
-- A file the tokenizer cannot read fails closed rather than scanning nothing.
-- expect: \[parse\] cannot tokenize: line 4: unterminated dollar quote
DO $$
BEGIN
    INSERT INTO inbox_note (note) SELECT key_hash FROM api_key;
END
$x$;
