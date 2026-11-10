
CREATE TABLE race_day (
 id UUID PRIMARY KEY, event_date DATE NOT NULL, display_number INTEGER NOT NULL CHECK(display_number BETWEEN 1 AND 999),
 currency TEXT NOT NULL CHECK(length(trim(currency))>0), time_zone TEXT NOT NULL, rules_version TEXT NOT NULL CHECK(length(trim(rules_version))>0),
 maximum_coupon_legs INTEGER NOT NULL CHECK(maximum_coupon_legs>0), maximum_open_races INTEGER NOT NULL CHECK(maximum_open_races>0),
 no_winner_policy TEXT NOT NULL CHECK(no_winner_policy='HOUSE_RETAINS_POOL')
);
CREATE TABLE race_day_state (
 race_day_id UUID PRIMARY KEY REFERENCES race_day, phase TEXT NOT NULL CHECK(phase IN ('DRAFT','READY','RUNNING','FINALIZING','COMPLETED','CLOSED')),
 version BIGINT NOT NULL CHECK(version>=0)
);
CREATE TABLE race (
 id UUID PRIMARY KEY, race_day_id UUID NOT NULL REFERENCES race_day, number INTEGER NOT NULL CHECK(number>0),
 title TEXT NOT NULL, racecourse_name TEXT NOT NULL CHECK(length(trim(racecourse_name))>0),
 UNIQUE(race_day_id,number), UNIQUE(race_day_id,id)
);
CREATE TABLE race_runner (
 race_id UUID NOT NULL REFERENCES race, horse_id UUID NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal>=0),
 program_number TEXT NOT NULL, display_name TEXT NOT NULL,
 PRIMARY KEY(race_id,horse_id), UNIQUE(race_id,program_number), UNIQUE(race_id,ordinal)
);
CREATE TABLE race_result (
 race_id UUID NOT NULL REFERENCES race, revision TEXT NOT NULL CHECK(length(trim(revision))>0),
 status TEXT NOT NULL CHECK(status IN ('PROPOSED','VERIFIED')), kind TEXT NOT NULL CHECK(kind IN ('NORMAL','EXCEPTIONAL')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), verified_at TIMESTAMPTZ,
 sequence BIGINT GENERATED ALWAYS AS IDENTITY, PRIMARY KEY(race_id,revision),
 CHECK((status='VERIFIED')=(verified_at IS NOT NULL))
);
CREATE UNIQUE INDEX one_verified_result ON race_result(race_id) WHERE status='VERIFIED';
CREATE TABLE race_result_finisher (
 race_id UUID NOT NULL, revision TEXT NOT NULL, position INTEGER NOT NULL CHECK(position>0), horse_id UUID NOT NULL,
 PRIMARY KEY(race_id,revision,position), UNIQUE(race_id,revision,horse_id),
 FOREIGN KEY(race_id,revision) REFERENCES race_result,
 FOREIGN KEY(race_id,horse_id) REFERENCES race_runner
);
CREATE TABLE race_state (
 race_id UUID PRIMARY KEY REFERENCES race, phase TEXT NOT NULL CHECK(phase IN ('SCHEDULED','BETTING_OPEN','BETTING_CLOSED','RESULT_PROPOSED','RESULT_VERIFIED','SETTLED','VOIDED')),
 result_revision TEXT, version BIGINT NOT NULL CHECK(version>=0),
 FOREIGN KEY(race_id,result_revision) REFERENCES race_result,
 CHECK(phase NOT IN ('RESULT_PROPOSED','RESULT_VERIFIED','SETTLED') OR result_revision IS NOT NULL)
);
CREATE TABLE race_scratch (
 race_id UUID NOT NULL, horse_id UUID NOT NULL, scratched_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(race_id,horse_id), FOREIGN KEY(race_id,horse_id) REFERENCES race_runner
);
CREATE TABLE pool_rules (
 race_id UUID NOT NULL REFERENCES race, bet_type TEXT NOT NULL CHECK(bet_type IN ('WIN','QUINELLA','EXACTA','TRIFECTA')),
 unit_stake BIGINT NOT NULL CHECK(unit_stake>0), take_basis_points INTEGER NOT NULL CHECK(take_basis_points BETWEEN 0 AND 10000),
 PRIMARY KEY(race_id,bet_type)
);
CREATE TABLE pool_state (
 race_id UUID NOT NULL, bet_type TEXT NOT NULL, valid_stake BIGINT NOT NULL CHECK(valid_stake>=0),
 active_units BIGINT NOT NULL CHECK(active_units>=0), version BIGINT NOT NULL CHECK(version>=0),
 PRIMARY KEY(race_id,bet_type), FOREIGN KEY(race_id,bet_type) REFERENCES pool_rules
);
CREATE TABLE pool_selection_units (
 race_id UUID NOT NULL, bet_type TEXT NOT NULL, canonical TEXT NOT NULL, horse_1 UUID NOT NULL, horse_2 UUID, horse_3 UUID,
 units BIGINT NOT NULL CHECK(units>0), PRIMARY KEY(race_id,bet_type,canonical),
 FOREIGN KEY(race_id,bet_type) REFERENCES pool_state,
 FOREIGN KEY(race_id,horse_1) REFERENCES race_runner, FOREIGN KEY(race_id,horse_2) REFERENCES race_runner, FOREIGN KEY(race_id,horse_3) REFERENCES race_runner,
 CHECK((bet_type='WIN' AND horse_2 IS NULL AND horse_3 IS NULL) OR
 (bet_type IN ('QUINELLA','EXACTA') AND horse_2 IS NOT NULL AND horse_3 IS NULL) OR
 (bet_type='TRIFECTA' AND horse_2 IS NOT NULL AND horse_3 IS NOT NULL)),
 CHECK(horse_1<>horse_2 AND horse_1<>horse_3 AND horse_2<>horse_3),
 CHECK(bet_type<>'QUINELLA' OR horse_1::text<horse_2::text)
);
CREATE TABLE player_entry (
 race_day_id UUID NOT NULL REFERENCES race_day, player_id UUID NOT NULL, opening_paid BIGINT NOT NULL CHECK(opening_paid>=0),
 opening_freebet BIGINT NOT NULL CHECK(opening_freebet>=0), PRIMARY KEY(race_day_id,player_id)
);
CREATE TABLE account_balance (
 race_day_id UUID NOT NULL, player_id UUID NOT NULL, balance BIGINT NOT NULL CHECK(balance>=0),
 accepted_stakes BIGINT NOT NULL CHECK(accepted_stakes>=0), refunded_stakes BIGINT NOT NULL CHECK(refunded_stakes BETWEEN 0 AND accepted_stakes),
 winnings BIGINT NOT NULL CHECK(winnings>=0), version BIGINT NOT NULL CHECK(version>=0), cashed_out BOOLEAN NOT NULL DEFAULT false,
 PRIMARY KEY(race_day_id,player_id), FOREIGN KEY(race_day_id,player_id) REFERENCES player_entry,
 CHECK(NOT cashed_out OR balance=0)
);
CREATE TABLE coupon (
 id UUID PRIMARY KEY, race_day_id UUID NOT NULL, race_id UUID NOT NULL, player_id UUID NOT NULL,
 bet_type TEXT NOT NULL, selection_kind TEXT NOT NULL CHECK(selection_kind IN ('POSITIONAL','BOX')),
 units BIGINT NOT NULL CHECK(units>0), unit_stake BIGINT NOT NULL CHECK(unit_stake>0), total_stake BIGINT NOT NULL CHECK(total_stake>0),
 purchased_at TIMESTAMPTZ NOT NULL, pos_number INTEGER NOT NULL CHECK(pos_number>0), cashier_id TEXT NOT NULL CHECK(length(trim(cashier_id))>0),
 UNIQUE(race_day_id,player_id,id), UNIQUE(id,race_id,bet_type),
 FOREIGN KEY(race_day_id,race_id) REFERENCES race(race_day_id,id),
 FOREIGN KEY(race_day_id,player_id) REFERENCES player_entry, FOREIGN KEY(race_id,bet_type) REFERENCES pool_rules
);
CREATE TABLE coupon_selection (
 coupon_id UUID NOT NULL REFERENCES coupon, ordinal INTEGER NOT NULL CHECK(ordinal>=0), choice TEXT NOT NULL CHECK(choice IN ('HORSE','WALL','BOX')),
 horse_id UUID, PRIMARY KEY(coupon_id,ordinal), CHECK((choice='WALL')=(horse_id IS NULL))
);
CREATE TABLE coupon_eligibility (
 coupon_id UUID NOT NULL REFERENCES coupon, horse_id UUID NOT NULL, PRIMARY KEY(coupon_id,horse_id)
);
CREATE TABLE coupon_leg (
 coupon_id UUID NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal>=0), race_id UUID NOT NULL, bet_type TEXT NOT NULL,
 canonical TEXT NOT NULL, horse_1 UUID NOT NULL, horse_2 UUID, horse_3 UUID,
 units BIGINT NOT NULL CHECK(units>0), stake BIGINT NOT NULL CHECK(stake>0),
 PRIMARY KEY(coupon_id,ordinal), UNIQUE(coupon_id,canonical),
 FOREIGN KEY(coupon_id,race_id,bet_type) REFERENCES coupon(id,race_id,bet_type),
 FOREIGN KEY(race_id,horse_1) REFERENCES race_runner, FOREIGN KEY(race_id,horse_2) REFERENCES race_runner, FOREIGN KEY(race_id,horse_3) REFERENCES race_runner,
 CHECK((bet_type='WIN' AND horse_2 IS NULL AND horse_3 IS NULL) OR
 (bet_type IN ('QUINELLA','EXACTA') AND horse_2 IS NOT NULL AND horse_3 IS NULL) OR
 (bet_type='TRIFECTA' AND horse_2 IS NOT NULL AND horse_3 IS NOT NULL)),
 CHECK(horse_1<>horse_2 AND horse_1<>horse_3 AND horse_2<>horse_3),
 CHECK(bet_type<>'QUINELLA' OR horse_1::text<horse_2::text)
);
CREATE TABLE settlement (
 id UUID PRIMARY KEY, race_id UUID NOT NULL REFERENCES race, result_revision TEXT NOT NULL,
 UNIQUE(id,race_id), FOREIGN KEY(race_id,result_revision) REFERENCES race_result
);
CREATE TABLE pool_settlement (
 settlement_id UUID NOT NULL, race_id UUID NOT NULL, bet_type TEXT NOT NULL,
 valid_stake BIGINT NOT NULL CHECK(valid_stake>=0), winning_units BIGINT NOT NULL CHECK(winning_units>=0),
 distributable BIGINT NOT NULL CHECK(distributable>=0), dividend_per_unit BIGINT NOT NULL CHECK(dividend_per_unit>=0),
 distributed BIGINT NOT NULL CHECK(distributed>=0), house_allocation BIGINT NOT NULL CHECK(house_allocation>=0),
 pool_version BIGINT NOT NULL CHECK(pool_version>=0), PRIMARY KEY(race_id,bet_type), UNIQUE(settlement_id,race_id,bet_type),
 FOREIGN KEY(settlement_id,race_id) REFERENCES settlement(id,race_id), FOREIGN KEY(race_id,bet_type) REFERENCES pool_rules,
 CHECK(distributed::numeric=dividend_per_unit::numeric*winning_units), CHECK(valid_stake::numeric=distributed::numeric+house_allocation),
 CHECK(distributed<=distributable AND distributable<=valid_stake)
);
CREATE TABLE leg_resolution (
 coupon_id UUID NOT NULL, ordinal INTEGER NOT NULL, outcome TEXT NOT NULL CHECK(outcome IN ('REFUNDED','WON','LOST')),
 amount BIGINT NOT NULL CHECK(amount>=0), settlement_id UUID REFERENCES settlement, resolved_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(coupon_id,ordinal), FOREIGN KEY(coupon_id,ordinal) REFERENCES coupon_leg,
 CHECK((outcome='REFUNDED')=(settlement_id IS NULL)), CHECK(outcome<>'LOST' OR amount=0)
);
CREATE TABLE account_movement (
 sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, race_day_id UUID NOT NULL, player_id UUID NOT NULL,
 movement_key TEXT NOT NULL CHECK(length(trim(movement_key))>0), movement_type TEXT NOT NULL CHECK(movement_type IN ('COUPON_STAKE','LEG_REFUND','WINNINGS','FINAL_WITHDRAWAL','PROMOTIONAL_FORFEITURE')),
 amount BIGINT NOT NULL CHECK(amount>=0), coupon_id UUID, leg_ordinal INTEGER, settlement_id UUID REFERENCES settlement,
 created_at TIMESTAMPTZ NOT NULL, UNIQUE(race_day_id,player_id,movement_key),
 FOREIGN KEY(race_day_id,player_id) REFERENCES player_entry,
 FOREIGN KEY(race_day_id,player_id,coupon_id) REFERENCES coupon(race_day_id,player_id,id),
 FOREIGN KEY(coupon_id,leg_ordinal) REFERENCES coupon_leg,
 CHECK(leg_ordinal IS NULL OR coupon_id IS NOT NULL),
 CHECK((movement_type='COUPON_STAKE' AND coupon_id IS NOT NULL AND leg_ordinal IS NULL AND settlement_id IS NULL AND amount>0) OR
 (movement_type='LEG_REFUND' AND leg_ordinal IS NOT NULL AND settlement_id IS NULL AND amount>0) OR
 (movement_type='WINNINGS' AND leg_ordinal IS NOT NULL AND settlement_id IS NOT NULL) OR
 (movement_type IN ('FINAL_WITHDRAWAL','PROMOTIONAL_FORFEITURE') AND coupon_id IS NULL AND leg_ordinal IS NULL AND settlement_id IS NULL))
);
CREATE UNIQUE INDEX one_coupon_debit ON account_movement(coupon_id) WHERE movement_type='COUPON_STAKE';
CREATE UNIQUE INDEX one_leg_credit ON account_movement(coupon_id,leg_ordinal) WHERE movement_type IN ('LEG_REFUND','WINNINGS');
CREATE UNIQUE INDEX one_cashout_movement ON account_movement(race_day_id,player_id,movement_type) WHERE movement_type IN ('FINAL_WITHDRAWAL','PROMOTIONAL_FORFEITURE');
CREATE TABLE payout (
 settlement_id UUID NOT NULL REFERENCES settlement, race_day_id UUID NOT NULL, player_id UUID NOT NULL,
 coupon_id UUID NOT NULL, leg_ordinal INTEGER NOT NULL, amount BIGINT NOT NULL CHECK(amount>=0), movement_key TEXT NOT NULL,
 PRIMARY KEY(coupon_id,leg_ordinal), FOREIGN KEY(coupon_id,leg_ordinal) REFERENCES leg_resolution,
 FOREIGN KEY(race_day_id,player_id,coupon_id) REFERENCES coupon(race_day_id,player_id,id),
 FOREIGN KEY(race_day_id,player_id,movement_key) REFERENCES account_movement(race_day_id,player_id,movement_key),
 UNIQUE(race_day_id,player_id,movement_key)
);
CREATE TABLE command_idempotency (
 operation TEXT NOT NULL, scope TEXT NOT NULL, idempotency_key TEXT NOT NULL, fingerprint TEXT NOT NULL CHECK(length(fingerprint)>0),
 result_reference TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ,
 PRIMARY KEY(operation,scope,idempotency_key), CHECK((completed_at IS NULL)=(result_reference IS NULL))
);
CREATE FUNCTION reject_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'historical record is immutable: %', TG_TABLE_NAME; END $$;

CREATE TRIGGER immutable_race_day BEFORE UPDATE OR DELETE ON race_day FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_race BEFORE UPDATE OR DELETE ON race FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_race_runner BEFORE UPDATE OR DELETE ON race_runner FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_race_scratch BEFORE UPDATE OR DELETE ON race_scratch FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_race_result_finisher BEFORE UPDATE OR DELETE ON race_result_finisher FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_pool_rules BEFORE UPDATE OR DELETE ON pool_rules FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_player_entry BEFORE UPDATE OR DELETE ON player_entry FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_coupon BEFORE UPDATE OR DELETE ON coupon FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_coupon_selection BEFORE UPDATE OR DELETE ON coupon_selection FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_coupon_eligibility BEFORE UPDATE OR DELETE ON coupon_eligibility FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_coupon_leg BEFORE UPDATE OR DELETE ON coupon_leg FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_settlement BEFORE UPDATE OR DELETE ON settlement FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_pool_settlement BEFORE UPDATE OR DELETE ON pool_settlement FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_leg_resolution BEFORE UPDATE OR DELETE ON leg_resolution FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_account_movement BEFORE UPDATE OR DELETE ON account_movement FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_payout BEFORE UPDATE OR DELETE ON payout FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE FUNCTION require_completed_command() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM command_idempotency WHERE operation=NEW.operation AND scope=NEW.scope AND idempotency_key=NEW.idempotency_key AND completed_at IS NULL)
 THEN RAISE EXCEPTION 'idempotency reservation must complete in its business transaction'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER completed_command AFTER INSERT OR UPDATE ON command_idempotency
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_completed_command();
CREATE INDEX coupon_race ON coupon(race_day_id,race_id,purchased_at,id);
CREATE INDEX movement_account ON account_movement(race_day_id,player_id,sequence);

-- Historical results may only advance from proposal to verification.
CREATE FUNCTION protect_result_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'result revisions cannot be deleted'; END IF;
 IF OLD.status<>'PROPOSED' OR NEW.status<>'VERIFIED' OR NEW.verified_at IS NULL OR
 (to_jsonb(NEW)-'status'-'verified_at') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'verified_at')
 THEN RAISE EXCEPTION 'only verification of an unchanged proposal is allowed'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_result BEFORE UPDATE OR DELETE ON race_result FOR EACH ROW EXECUTE FUNCTION protect_result_revision();

CREATE FUNCTION validate_selection_roster() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.horse_id IS NOT NULL AND NOT EXISTS(
 SELECT 1 FROM coupon c JOIN race_runner r ON r.race_id=c.race_id
 WHERE c.id=NEW.coupon_id AND r.horse_id=NEW.horse_id)
 THEN RAISE EXCEPTION 'coupon horse outside original race roster'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER selection_roster BEFORE INSERT ON coupon_selection FOR EACH ROW EXECUTE FUNCTION validate_selection_roster();
CREATE TRIGGER eligibility_roster BEFORE INSERT ON coupon_eligibility FOR EACH ROW EXECUTE FUNCTION validate_selection_roster();

CREATE FUNCTION validate_complete_coupon() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM coupon_leg WHERE coupon_id=NEW.id) OR
 NOT EXISTS(SELECT 1 FROM coupon_selection WHERE coupon_id=NEW.id) OR
 NOT EXISTS(SELECT 1 FROM coupon_eligibility WHERE coupon_id=NEW.id) OR
 (SELECT sum(stake::numeric) FROM coupon_leg WHERE coupon_id=NEW.id)<>NEW.total_stake OR
 EXISTS(SELECT 1 FROM coupon_leg WHERE coupon_id=NEW.id AND
 (units<>NEW.units OR stake::numeric<>units::numeric*NEW.unit_stake))
 THEN RAISE EXCEPTION 'incomplete or inconsistent purchased coupon'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER complete_coupon AFTER INSERT ON coupon DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION validate_complete_coupon();

CREATE FUNCTION validate_resolution_context() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.outcome='REFUNDED' THEN
 IF NOT EXISTS(SELECT 1 FROM coupon_leg WHERE coupon_id=NEW.coupon_id AND ordinal=NEW.ordinal AND stake=NEW.amount)
 THEN RAISE EXCEPTION 'refund must equal purchased leg stake'; END IF;
 ELSE
 IF NOT EXISTS(SELECT 1 FROM coupon c JOIN settlement s ON s.race_id=c.race_id
 JOIN pool_settlement p ON p.settlement_id=s.id AND p.race_id=c.race_id AND p.bet_type=c.bet_type
 WHERE c.id=NEW.coupon_id AND s.id=NEW.settlement_id)
 THEN RAISE EXCEPTION 'resolution belongs to a different settlement pool'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER resolution_context BEFORE INSERT ON leg_resolution FOR EACH ROW EXECUTE FUNCTION validate_resolution_context();

CREATE FUNCTION validate_settlement_context() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM race_result WHERE race_id=NEW.race_id AND revision=NEW.result_revision AND status='VERIFIED')
 THEN RAISE EXCEPTION 'settlement requires verified result'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER settlement_context BEFORE INSERT ON settlement FOR EACH ROW EXECUTE FUNCTION validate_settlement_context();
CREATE FUNCTION validate_complete_settlement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (SELECT count(*) FROM pool_settlement WHERE settlement_id=NEW.id)<>4
 THEN RAISE EXCEPTION 'settlement requires four pool records'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER complete_settlement AFTER INSERT ON settlement DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION validate_complete_settlement();

CREATE FUNCTION protect_completed_command() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.completed_at IS NOT NULL
 THEN RAISE EXCEPTION 'completed idempotency records are immutable'; END IF;
 IF (NEW.operation,NEW.scope,NEW.idempotency_key,NEW.fingerprint,NEW.created_at)
 IS DISTINCT FROM (OLD.operation,OLD.scope,OLD.idempotency_key,OLD.fingerprint,OLD.created_at)
 THEN RAISE EXCEPTION 'idempotency request cannot change'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_command BEFORE UPDATE OR DELETE ON command_idempotency FOR EACH ROW EXECUTE FUNCTION protect_completed_command();

CREATE FUNCTION validate_payout_credit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(
 SELECT 1 FROM account_movement m JOIN leg_resolution l ON l.coupon_id=m.coupon_id AND l.ordinal=m.leg_ordinal
 WHERE m.race_day_id=NEW.race_day_id AND m.player_id=NEW.player_id AND m.movement_key=NEW.movement_key
 AND m.coupon_id=NEW.coupon_id AND m.leg_ordinal=NEW.leg_ordinal AND m.movement_type='WINNINGS'
 AND m.amount=NEW.amount AND m.settlement_id=NEW.settlement_id AND l.outcome='WON'
 AND l.amount=NEW.amount AND l.settlement_id=NEW.settlement_id)
 THEN RAISE EXCEPTION 'payout must reference matching winnings credit and won resolution'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER payout_credit BEFORE INSERT ON payout FOR EACH ROW EXECUTE FUNCTION validate_payout_credit();
