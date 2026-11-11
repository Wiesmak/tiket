-- Deliberately independent of resource FKs: denied attempts may reference nonexistent resources.
CREATE TABLE operational_audit (
 id UUID PRIMARY KEY,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 actor TEXT NOT NULL,
 operation TEXT NOT NULL,
 resource_scope TEXT NOT NULL,
 correlation_id UUID NOT NULL,
 request_digest TEXT NOT NULL,
 outcome TEXT NOT NULL CHECK(outcome IN ('COMMITTED','REPLAYED','DENIED','REJECTED','FAILED')),
 failure_category TEXT NOT NULL DEFAULT ''
);
CREATE INDEX audit_scope_time ON operational_audit(resource_scope,recorded_at);
CREATE INDEX audit_actor_time ON operational_audit(actor,recorded_at);
CREATE FUNCTION protect_operational_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'operational audit is append only'; END $$;
CREATE TRIGGER immutable_operational_audit BEFORE UPDATE OR DELETE OR TRUNCATE ON operational_audit
 FOR EACH STATEMENT EXECUTE FUNCTION protect_operational_audit();
CREATE TABLE command_actor (
 operation TEXT NOT NULL, scope TEXT NOT NULL, idempotency_key TEXT NOT NULL, actor TEXT NOT NULL,
 PRIMARY KEY(operation,scope,idempotency_key),
 FOREIGN KEY(operation,scope,idempotency_key) REFERENCES command_idempotency(operation,scope,idempotency_key)
);
CREATE TRIGGER immutable_command_actor BEFORE UPDATE OR DELETE OR TRUNCATE ON command_actor
 FOR EACH STATEMENT EXECUTE FUNCTION protect_operational_audit();
