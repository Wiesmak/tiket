-- Preserve historical definitions while adding planned order.
ALTER TABLE race ADD COLUMN ordinal INTEGER;
ALTER TABLE race DISABLE TRIGGER immutable_race;
WITH ordered AS (
 SELECT id, (row_number() OVER (PARTITION BY race_day_id ORDER BY number)-1)::integer AS position FROM race
) UPDATE race SET ordinal=ordered.position FROM ordered WHERE race.id=ordered.id;
ALTER TABLE race ENABLE TRIGGER immutable_race;
ALTER TABLE race ALTER COLUMN ordinal SET NOT NULL;
ALTER TABLE race ADD CHECK(ordinal>=0), ADD UNIQUE(race_day_id,ordinal);

ALTER TABLE account_balance ADD COLUMN opening_version BIGINT NOT NULL DEFAULT 0
 CHECK(opening_version>=0 AND opening_version<=version);
CREATE TABLE opening_funding_adjustment (
 race_day_id UUID NOT NULL, player_id UUID NOT NULL, opening_version BIGINT NOT NULL CHECK(opening_version>0),
 added_paid BIGINT NOT NULL CHECK(added_paid>=0), added_promotional BIGINT NOT NULL CHECK(added_promotional>=0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(race_day_id,player_id,opening_version),
 FOREIGN KEY(race_day_id,player_id) REFERENCES account_balance,
 CHECK(added_paid>0 OR added_promotional>0)
);
CREATE TRIGGER immutable_opening_adjustment BEFORE UPDATE OR DELETE ON opening_funding_adjustment
 FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE FUNCTION authorize_opening_adjustment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE day_phase TEXT; account account_balance%ROWTYPE;
BEGIN
 SELECT phase INTO day_phase FROM race_day_state WHERE race_day_id=NEW.race_day_id FOR UPDATE;
 IF day_phase IS NULL OR day_phase NOT IN ('DRAFT','READY') THEN
  RAISE EXCEPTION 'opening funding frozen';
 END IF;
 SELECT * INTO account FROM account_balance WHERE race_day_id=NEW.race_day_id AND player_id=NEW.player_id FOR UPDATE;
 IF account.player_id IS NULL OR account.cashed_out OR account.version<>account.opening_version
 OR NEW.opening_version<>account.opening_version+1
 OR EXISTS(SELECT 1 FROM account_movement WHERE race_day_id=NEW.race_day_id AND player_id=NEW.player_id)
 OR EXISTS(SELECT 1 FROM coupon WHERE race_day_id=NEW.race_day_id AND player_id=NEW.player_id)
 THEN RAISE EXCEPTION 'opening funding requires an inactive enrolled account and next version'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER authorize_opening_adjustment BEFORE INSERT ON opening_funding_adjustment
 FOR EACH ROW EXECUTE FUNCTION authorize_opening_adjustment();
CREATE FUNCTION reconcile_opening_adjustments() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS (
 SELECT 1 FROM account_balance a JOIN player_entry e USING(race_day_id,player_id)
 WHERE a.race_day_id=NEW.race_day_id AND a.player_id=NEW.player_id AND (
 a.opening_version<>(SELECT count(*) FROM opening_funding_adjustment f WHERE f.race_day_id=a.race_day_id AND f.player_id=a.player_id)
 OR (a.version=a.opening_version AND a.balance::numeric<>e.opening_paid::numeric+e.opening_freebet::numeric+
 (SELECT coalesce(sum(added_paid::numeric+added_promotional::numeric),0) FROM opening_funding_adjustment f WHERE f.race_day_id=a.race_day_id AND f.player_id=a.player_id))))
 THEN RAISE EXCEPTION 'opening funding projection mismatch'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER reconcile_opening_adjustments AFTER INSERT ON opening_funding_adjustment
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reconcile_opening_adjustments();
CREATE CONSTRAINT TRIGGER reconcile_opening_balance AFTER INSERT OR UPDATE ON account_balance
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reconcile_opening_adjustments();
