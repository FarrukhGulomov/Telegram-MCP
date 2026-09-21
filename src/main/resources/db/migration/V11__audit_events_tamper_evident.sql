-- Security-audit finding SEC-R09: audit_events is meant to be the durable, trustworthy record
-- of every access decision this application makes, but until now nothing at the database level
-- stopped a row from being quietly rewritten or removed by anyone with direct database access
-- (a leaked service credential, a compromised admin connection, or a future application bug) --
-- exactly the kind of tampering an audit trail exists to make detectable. Application code never
-- updates or deletes an individual audit_events row (see AuditService, which only ever inserts),
-- so refusing both outright at the database level costs the application nothing.
--
-- The one legitimate exception is DemoDataCleanupRunner's `DELETE FROM tenants WHERE
-- demo_mode = true`, which cascades (via audit_events.tenant_id's ON DELETE CASCADE) into a
-- full, deliberate erasure of a demo tenant's own audit trail alongside the rest of its data --
-- that is durable erasure of synthetic data, not tampering with a real record. pg_trigger_depth()
-- reliably tells the two apart with no application-code changes: PostgreSQL implements FK cascade
-- actions as internal triggers on the referenced table, so this trigger's own execution sits one
-- level deeper (depth 2: the cascade's internal trigger, then this one) when it is only firing as
-- a *side effect* of that cascade, versus depth 1 (itself alone) when a direct
-- `DELETE FROM audit_events ...` is issued as its own top-level statement. Only the latter is
-- refused. (TRUNCATE, used by the test suite to reset state between tests, does not fire
-- per-row triggers at all, so it is unaffected either way.)
-- ERRCODE '23514' (check_violation) rather than plain plpgsql's default P0001: it is one of
-- the codes Spring's SQLErrorCodeSQLExceptionTranslator maps to DataIntegrityViolationException,
-- so callers see a normal, recognisable Spring data-access exception instead of an opaque
-- UncategorizedSQLException.
CREATE FUNCTION audit_events_deny_tamper() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'audit_events rows are immutable and cannot be updated (id=%)', OLD.id
            USING ERRCODE = '23514';
    ELSIF TG_OP = 'DELETE' THEN
        -- pg_trigger_depth() counts this trigger's own execution, so depth 1 means nothing
        -- outside it is on the call stack -- a direct, top-level DELETE against this table.
        IF pg_trigger_depth() <= 1 THEN
            RAISE EXCEPTION 'audit_events rows cannot be deleted directly (id=%); only cascading '
                'removal alongside their tenant is permitted', OLD.id
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW
    EXECUTE FUNCTION audit_events_deny_tamper();
