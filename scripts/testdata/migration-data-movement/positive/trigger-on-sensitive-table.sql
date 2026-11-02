-- expect-exit: 1
-- The function never names the table it copies from: only the trigger does.
-- expect: V23__fixture\.sql:13: \[trigger\] trigger on/referencing human_account
CREATE FUNCTION mirror_contact() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO inbox_note (note) VALUES (NEW.contact_email);
    RETURN NEW;
END
$$;
CREATE TRIGGER mirror_contact
    AFTER INSERT OR UPDATE
    ON human_account
    FOR EACH ROW EXECUTE FUNCTION mirror_contact();
