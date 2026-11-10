package pl.umamusume.tiket.backend.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.umamusume.tiket.core.betting.BetType
import pl.umamusume.tiket.core.identity.HorseId
import pl.umamusume.tiket.core.identity.PoolKey
import pl.umamusume.tiket.core.identity.RaceDayId
import pl.umamusume.tiket.core.identity.RaceId
import pl.umamusume.tiket.core.money.TakeRate
import pl.umamusume.tiket.core.pool.NoWinnerPolicy
import pl.umamusume.tiket.core.pool.PoolRules
import pl.umamusume.tiket.core.race.*
import pl.umamusume.tiket.core.raceday.RaceDayDefinition
import pl.umamusume.tiket.core.raceday.RaceDayPhase
import pl.umamusume.tiket.core.raceday.RaceDayRules
import pl.umamusume.tiket.core.raceday.RaceDayState
import pl.umamusume.tiket.core.validation.getOrThrow
import java.time.Instant

@Repository
class RaceDayRepository(
    private val jdbc: JdbcTemplate,
    private val tx: PersistenceTransactions,
    private val races: RaceRepository
) {
    fun insert(day: RaceDayDefinition, state: RaceDayState = RaceDayState()) = tx.required {
        jdbc.update(
            "INSERT INTO race_day VALUES (?,?,?,?,?,?,?,?,?)",
            day.id.value,
            day.date,
            day.displayNumber,
            day.currency,
            day.timeZone.id,
            day.rulesVersion,
            day.rules.maximumCouponLegs,
            day.rules.maximumOpenRaces,
            day.rules.noWinnerPolicy.name
        )
        jdbc.update("INSERT INTO race_day_state VALUES (?,?,?)", day.id.value, state.phase.name, state.version)
        day.races.forEachIndexed { ordinal, race -> races.insert(day.id, race, ordinal) }
        day.pools.forEach { (key, rules) ->
            jdbc.update(
                "INSERT INTO pool_rules VALUES (?,?,?,?)",
                key.raceId.value,
                key.betType.name,
                rules.unitStake.amount,
                rules.takeRate.basisPoints
            )
            jdbc.update("INSERT INTO pool_state VALUES (?,?,0,0,0)", key.raceId.value, key.betType.name)
        }; Unit
    }

    fun load(id: RaceDayId): RaceDayDefinition? = jdbc.one("SELECT * FROM race_day WHERE id=?", id.value) { r ->
        val definitions = races.list(id)
        val pools =
            jdbc.query(
                "SELECT p.* FROM pool_rules p JOIN race r ON r.id=p.race_id WHERE r.race_day_id=? ORDER BY r.number,p.bet_type",
                { rs, _ ->
                    PoolKey(
                        RaceId(rs.uuid("race_id")),
                        BetType.valueOf(rs.getString("bet_type"))
                    ) to PoolRules(rs.money("unit_stake"), TakeRate(rs.getInt("take_basis_points")))
                }, id.value
            ).toMap()
        RaceDayDefinition(
            id,
            r.getObject("event_date", java.time.LocalDate::class.java),
            r.getString("currency"),
            definitions,
            r.getString("rules_version"),
            pools,
            RaceDayRules(
                r.getInt("maximum_coupon_legs"),
                r.getInt("maximum_open_races"),
                NoWinnerPolicy.valueOf(r.getString("no_winner_policy"))
            ),
            r.getInt("display_number"),
            java.time.ZoneId.of(r.getString("time_zone"))
        )
    }

    fun state(id: RaceDayId): RaceDayState? = jdbc.one(
        "SELECT * FROM race_day_state WHERE race_day_id=?",
        id.value
    ) { RaceDayState(RaceDayPhase.valueOf(it.getString("phase")), it.getLong("version")) }

    fun update(id: RaceDayId, expected: Long, next: RaceDayState) {
        require(next.version == Math.incrementExact(expected))
        changed(
            jdbc.update(
                "UPDATE race_day_state SET phase=?,version=? WHERE race_day_id=? AND version=?",
                next.phase.name,
                next.version,
                id.value,
                expected
            )
        )
    }
}

@Repository
class RaceRepository(private val jdbc: JdbcTemplate, private val tx: PersistenceTransactions) {
    fun insert(day: RaceDayId, race: RaceDefinition, ordinal: Int = race.number - 1) = tx.required {
        jdbc.update(
            "INSERT INTO race(id,race_day_id,number,title,racecourse_name,ordinal) VALUES (?,?,?,?,?,?)",
            race.id.value,
            day.value,
            race.number,
            race.title,
            race.racecourseName,
            ordinal
        )
        race.runners.forEachIndexed { i, r ->
            jdbc.update(
                "INSERT INTO race_runner VALUES (?,?,?,?,?)",
                race.id.value,
                r.horseId.value,
                i,
                r.programNumber,
                r.displayName
            )
        }
        jdbc.update("INSERT INTO race_state VALUES (?,'SCHEDULED',NULL,0)", race.id.value); Unit
    }

    fun list(day: RaceDayId): List<RaceDefinition> = jdbc.query(
        "SELECT id FROM race WHERE race_day_id=? ORDER BY ordinal",
        { r, _ -> RaceId(r.uuid("id")) },
        day.value
    ).map { load(day, it)!! }

    fun load(day: RaceDayId, id: RaceId): RaceDefinition? =
        jdbc.one("SELECT * FROM race WHERE race_day_id=? AND id=?", day.value, id.value) { r ->
            val runners = jdbc.query(
                "SELECT * FROM race_runner WHERE race_id=? ORDER BY ordinal",
                { s, _ ->
                    RaceRunner(
                        HorseId(s.uuid("horse_id")),
                        s.getString("program_number"),
                        s.getString("display_name")
                    )
                },
                id.value
            )
            RaceDefinition(id, r.getInt("number"), r.getString("title"), runners, r.getString("racecourse_name"))
        }

    fun state(day: RaceDayId, id: RaceId): RaceState? {
        val definition = load(day, id) ?: return null
        return jdbc.one("SELECT * FROM race_state WHERE race_id=?", id.value) { r ->
            val scratches = jdbc.query(
                "SELECT horse_id FROM race_scratch WHERE race_id=? ORDER BY horse_id",
                { s, _ -> HorseId(s.uuid("horse_id")) },
                id.value
            ).toSet()
            val result = r.getString("result_revision")?.let { revision ->
                readResult(jdbc, definition, revision) ?: throw InvalidPersistenceException("Missing result")
            }
            val phase = RacePhase.valueOf(r.getString("phase"))
            checked(
                phase !in setOf(
                    RacePhase.RESULT_VERIFIED,
                    RacePhase.SETTLED
                ) || result?.status == ResultStatus.VERIFIED, "State result status mismatch"
            )
            checked(phase != RacePhase.RESULT_PROPOSED || result?.status == ResultStatus.PROPOSED, "Proposal mismatch")
            RaceState(id, phase, scratches, result, r.getLong("version"))
        }
    }

    fun update(day: RaceDayId, expected: Long, next: RaceState, at: Instant = Instant.now()) = tx.required {
        require(next.version == Math.incrementExact(expected))
        val definition = load(day, next.raceId) ?: throw InvalidPersistenceException("Foreign race")
        checked(definition.roster.containsAll(next.scratched), "Scratch outside roster")
        checked(state(day, next.raceId)!!.scratched.all { it in next.scratched }, "Cannot remove scratches")
        next.result?.let { ResultValidator.validate(definition, next, it).getOrThrow() }
        changed(
            jdbc.update(
                "UPDATE race_state SET phase=?,result_revision=?,version=? WHERE race_id=? AND version=? AND EXISTS(SELECT 1 FROM race WHERE id=? AND race_day_id=?)",
                next.phase.name,
                next.result?.revision,
                next.version,
                next.raceId.value,
                expected,
                next.raceId.value,
                day.value
            )
        )
        next.scratched.forEach {
            jdbc.update(
                "INSERT INTO race_scratch VALUES (?,?,?) ON CONFLICT DO NOTHING",
                next.raceId.value,
                it.value,
                timestamp(at)
            )
        }; Unit
    }
}

internal fun readResult(jdbc: JdbcTemplate, race: RaceDefinition, revision: String): RaceResult? =
    jdbc.one("SELECT * FROM race_result WHERE race_id=? AND revision=?", race.id.value, revision) { r ->
        val horses = jdbc.query(
            "SELECT horse_id FROM race_result_finisher WHERE race_id=? AND revision=? ORDER BY position",
            { s, _ -> HorseId(s.uuid("horse_id")) },
            race.id.value,
            revision
        )
        val result = RaceResult(
            race.id,
            revision,
            horses,
            ResultStatus.valueOf(r.getString("status")),
            ResultKind.valueOf(r.getString("kind"))
        )
        checked(
            horses.isNotEmpty() && horses.distinct().size == horses.size && race.roster.containsAll(horses),
            "Invalid result roster"
        )
        checked(result.status != ResultStatus.VERIFIED || horses.size >= 3, "Incomplete verified result")
        result
    }

@Repository
class ResultRepository(
    private val jdbc: JdbcTemplate,
    private val tx: PersistenceTransactions,
    private val races: RaceRepository
) {
    fun insert(day: RaceDayId, result: RaceResult, at: Instant = Instant.now()) = tx.required {
        val race = races.load(day, result.raceId) ?: throw InvalidPersistenceException("Foreign race")
        checked(
            result.revision.isNotBlank() && result.finishers.isNotEmpty() && result.finishers.distinct().size == result.finishers.size && race.roster.containsAll(
                result.finishers
            ), "Invalid result"
        )
        checked(result.status != ResultStatus.VERIFIED || result.finishers.size >= 3, "Incomplete verified result")
        jdbc.update(
            "INSERT INTO race_result(race_id,revision,status,kind,created_at,verified_at) VALUES (?,?,?,?,?,?)",
            result.raceId.value,
            result.revision,
            result.status.name,
            result.kind.name,
            timestamp(at),
            if (result.status == ResultStatus.VERIFIED) timestamp(at) else null
        )
        result.finishers.forEachIndexed { i, h ->
            jdbc.update(
                "INSERT INTO race_result_finisher VALUES (?,?,?,?)",
                result.raceId.value,
                result.revision,
                i + 1,
                h.value
            )
        }; Unit
    }

    fun verify(day: RaceDayId, id: RaceId, revision: String, at: Instant = Instant.now()) {
        val result = load(day, id, revision) ?: throw InvalidPersistenceException("Missing proposal")
        checked(result.finishers.size >= 3 && result.kind == ResultKind.NORMAL, "Unsupported verification")
        changed(
            jdbc.update(
                "UPDATE race_result SET status='VERIFIED',verified_at=? WHERE race_id=? AND revision=? AND status='PROPOSED'",
                timestamp(at),
                id.value,
                revision
            )
        )
    }

    fun load(day: RaceDayId, id: RaceId, revision: String): RaceResult? =
        races.load(day, id)?.let { readResult(jdbc, it, revision) }

    fun list(day: RaceDayId, id: RaceId): List<RaceResult> {
        if (races.load(day, id) == null) return emptyList()
        return jdbc.query(
            "SELECT revision FROM race_result WHERE race_id=? ORDER BY sequence",
            { r, _ -> r.getString(1) },
            id.value
        ).map { load(day, id, it)!! }
    }
}
