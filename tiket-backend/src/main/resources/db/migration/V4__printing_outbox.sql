CREATE TABLE print_job (
 id UUID PRIMARY KEY, coupon_id UUID NOT NULL REFERENCES coupon,
 job_type TEXT NOT NULL CHECK(job_type IN ('ORIGINAL','REPRINT')),
 status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','CLAIMED','RETRYING','DISPATCHED','PERMANENTLY_FAILED')),
 enqueued_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
 claim_owner UUID, lease_until TIMESTAMPTZ, last_error TEXT NOT NULL DEFAULT '',
 CHECK((status='CLAIMED')=(claim_owner IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE UNIQUE INDEX one_original_print ON print_job(coupon_id) WHERE job_type='ORIGINAL';
CREATE INDEX pending_print_jobs ON print_job(next_attempt_at,enqueued_at) WHERE status IN ('PENDING','RETRYING','CLAIMED');
CREATE TABLE print_attempt (
 id UUID PRIMARY KEY, print_job_id UUID NOT NULL REFERENCES print_job,
 started_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ,
 result TEXT NOT NULL CHECK(result IN ('STARTED','DISPATCHED','RETRYABLE_FAILURE','PERMANENT_FAILURE','LEASE_EXPIRED')),
 error TEXT NOT NULL DEFAULT '',
 CHECK((result='STARTED')=(completed_at IS NULL))
);
CREATE INDEX print_attempt_history ON print_attempt(print_job_id,started_at,id);
CREATE INDEX coupon_player ON coupon(race_day_id,player_id,purchased_at,id);

