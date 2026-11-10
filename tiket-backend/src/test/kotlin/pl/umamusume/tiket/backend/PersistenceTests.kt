package pl.umamusume.tiket.backend

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.umamusume.tiket.backend.persistence.*
import pl.umamusume.tiket.core.accounting.*
import pl.umamusume.tiket.core.betting.*
import pl.umamusume.tiket.core.coupon.LegOutcome
import pl.umamusume.tiket.core.coupon.LegResolution
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.money.Money
import pl.umamusume.tiket.core.money.TakeRate
import pl.umamusume.tiket.core.pool.PoolRules
import pl.umamusume.tiket.core.pool.PoolSnapshot
import pl.umamusume.tiket.core.race.*
import pl.umamusume.tiket.core.raceday.RaceDayDefinition
import pl.umamusume.tiket.core.raceday.RaceDayPhase
import pl.umamusume.tiket.core.raceday.RaceDayState
import pl.umamusume.tiket.core.validation.getOrThrow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistenceTests {
    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18-alpine")
    }

    lateinit var context: ConfigurableApplicationContext
    lateinit var jdbc: JdbcTemplate
    lateinit var tx: PersistenceTransactions
    lateinit var days: RaceDayRepository
    lateinit var races: RaceRepository
    lateinit var coupons: CouponRepository
    lateinit var accounts: AccountRepository
    lateinit var pools: PoolRepository
    lateinit var entries: PlayerEntryRepository
    lateinit var results: ResultRepository
    lateinit var resolutions: LegResolutionRepository
    lateinit var settlements: SettlementRepository
    lateinit var idempotency: IdempotencyRepository
    @BeforeAll
    fun start() {
        context = SpringApplicationBuilder(TiketBackendApplication::class.java).run(
            "--spring.datasource.url=${postgres.jdbcUrl}", "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}", "--spring.grpc.server.port=0"
        )
        jdbc = context.getBean(JdbcTemplate::class.java); tx = context.getBean(PersistenceTransactions::class.java)
        days = context.getBean(RaceDayRepository::class.java); races = context.getBean(RaceRepository::class.java)
        coupons = context.getBean(CouponRepository::class.java); accounts =
            context.getBean(AccountRepository::class.java)
        pools = context.getBean(PoolRepository::class.java); entries =
            context.getBean(PlayerEntryRepository::class.java)
        results = context.getBean(ResultRepository::class.java); resolutions =
            context.getBean(LegResolutionRepository::class.java)
        settlements = context.getBean(SettlementRepository::class.java); idempotency =
            context.getBean(IdempotencyRepository::class.java)
    }

    @AfterAll
    fun stop() {
        if (::context.isInitialized) context.close()
    }

    data class Fixture(val day: RaceDayDefinition, val entry: PlayerEntry) {
        val race get() = day.races.first()
        val horses get() = race.runners.map { it.horseId }
    }

    fun fixture(): Fixture {
        val shared = HorseId(UUID.randomUUID())
        fun race(n: Int) = RaceDefinition(
            RaceId(UUID.randomUUID()), n, "Race $n",
            (0..3).map {
                RaceRunner(
                    if (it == 0) shared else HorseId(UUID.randomUUID()),
                    "${n * 10 + it}",
                    "Runner $it"
                )
            }, "Course $n"
        )

        val races = listOf(race(1), race(2))
        val day = RaceDayDefinition(
            RaceDayId(UUID.randomUUID()), LocalDate.of(2026, 11, 10), "PLN", races, "v1",
            races.flatMap { r ->
                BetType.entries.map {
                    PoolKey(r.id, it) to PoolRules(
                        Money(100 + it.ordinal.toLong()),
                        TakeRate(1500)
                    )
                }
            }.toMap(),
            displayNumber = 42, timeZone = ZoneId.of("Europe/Warsaw")
        )
        days.insert(day)
        val entry = PlayerEntry(day.id, PlayerId(UUID.randomUUID()), Money(100000), Money(5000))
        entries.insert(entry)
        return Fixture(day, entry)
    }

    fun purchase(
        f: Fixture,
        type: BetType = BetType.WIN,
        selection: CouponSelection = CouponSelection.Positional(listOf(PositionChoice.Horse(f.horses[0])))
    ): pl.umamusume.tiket.core.betting.PurchasePlan =
        BetPlacementPolicy.purchase(
            f.day,
            RaceDayState(RaceDayPhase.RUNNING),
            f.race,
            RaceState(f.race.id, RacePhase.BETTING_OPEN),
            f.entry,
            accounts.load(f.day.id, f.entry.playerId)!!,
            type,
            selection,
            2,
            CouponId(UUID.randomUUID()),
            Instant.parse("2026-11-10T12:00:00Z")
        ).getOrThrow()

    fun save(f: Fixture, p: pl.umamusume.tiket.core.betting.PurchasePlan) = tx.required {
        coupons.insert(p.coupon, PurchaseMetadata(7, "cashier"), f.race.roster)
        val account = accounts.load(f.day.id, f.entry.playerId)!!
        accounts.append(account.version, AccountProjector.apply(account, p.debit).getOrThrow())
        Unit
    }

    @Test
    fun definitionsStatesAndTenantBoundaries() {
        val f = fixture();
        val loaded = days.load(f.day.id)!!
        assertThat(loaded.displayNumber).isEqualTo(42); assertThat(loaded.timeZone).isEqualTo(f.day.timeZone)
        assertThat(loaded.races.zip(f.day.races).all { (a, b) -> a.hasSameDefinition(b) }).isTrue()
        assertThat(loaded.pools).isEqualTo(f.day.pools)
        days.update(f.day.id, 0, RaceDayState(RaceDayPhase.READY, 1))
        assertThat(days.state(f.day.id)).isEqualTo(RaceDayState(RaceDayPhase.READY, 1))
        assertThatThrownBy { days.update(f.day.id, 0, RaceDayState(RaceDayPhase.READY, 1)) }.isInstanceOf(
            StaleVersionException::class.java
        )
        races.update(f.day.id, 0, RaceState(f.race.id, RacePhase.SCHEDULED, setOf(f.horses[3]), version = 1))
        assertThat(races.state(f.day.id, f.race.id)!!.scratched).containsExactly(f.horses[3])
        assertThat(races.load(f.day.id, f.race.id)!!.runners).isEqualTo(f.race.runners)
        val other = fixture()
        assertThat(races.load(other.day.id, f.race.id)).isNull()
        assertThat(accounts.load(other.day.id, f.entry.playerId)).isNull()
        assertThat(pools.load(other.day.id, PoolKey(f.race.id, BetType.WIN))).isNull()
        assertThat(f.day.races[0].runners[0].horseId).isEqualTo(f.day.races[1].runners[0].horseId)
        assertThat(f.day.races[0].runners[0].programNumber).isNotEqualTo(f.day.races[1].runners[0].programNumber)
    }

    @Test
    fun couponVariantsSurviveScratchWithFrozenLegs() {
        val f = fixture()
        val variants = listOf(
            BetType.WIN to CouponSelection.Positional(listOf(PositionChoice.Horse(f.horses[0]))),
            BetType.EXACTA to CouponSelection.Positional(
                listOf(
                    PositionChoice.Horse(f.horses[0]),
                    PositionChoice.Wall
                )
            ),
            BetType.QUINELLA to CouponSelection.Box(f.horses.reversed()),
            BetType.TRIFECTA to CouponSelection.Box(f.horses)
        )
        variants.forEach { (type, selection) ->
            val p = purchase(f, type, selection); save(f, p)
            val loaded = coupons.load(f.day.id, p.coupon.id)!!
            assertThat(loaded.coupon.selection).isEqualTo(p.coupon.selection)
            assertThat(loaded.coupon.legs).isEqualTo(p.coupon.legs)
            assertThat(loaded.coupon.totalStake).isEqualTo(p.coupon.totalStake)
            assertThat(loaded.metadata).isEqualTo(PurchaseMetadata(7, "cashier"))
        }
        val before = coupons.list(f.day.id, f.race.id)
        races.update(f.day.id, 0, RaceState(f.race.id, scratched = setOf(f.horses[3]), version = 1))
        assertThat(coupons.list(f.day.id, f.race.id).map { it.coupon.legs }).isEqualTo(before.map { it.coupon.legs })
        assertThat(AtomicBet.Quinella(f.horses[0], f.horses[1])).isEqualTo(AtomicBet.Quinella(f.horses[1], f.horses[0]))
        assertThatThrownBy {
            jdbc.update(
                "UPDATE coupon SET total_stake=1 WHERE id=?",
                before[0].coupon.id.value
            )
        }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
    }

    @Test
    fun ledgerRefundWinningsAndCashoutReconcile() {
        val f = fixture();
        val p = purchase(
            f,
            BetType.EXACTA,
            CouponSelection.Positional(listOf(PositionChoice.Horse(f.horses[0]), PositionChoice.Wall))
        )
        save(f, p)
        var account = accounts.load(f.day.id, f.entry.playerId)!!
        val refund = AccountMovement(
            "partial",
            MovementType.LEG_REFUND,
            p.coupon.legs[0].stake,
            p.coupon.id,
            p.coupon.legs[0].id
        )
        accounts.append(account.version, AccountProjector.apply(account, refund).getOrThrow())
        account = accounts.load(f.day.id, f.entry.playerId)!!
        assertThat(account.refundedStakes).isEqualTo(p.coupon.legs[0].stake)
        val cashed = AccountProjector.cashOut(account, RaceDayState(RaceDayPhase.COMPLETED)).getOrThrow()
        accounts.append(account.version, cashed)
        val loaded = accounts.load(f.day.id, f.entry.playerId)!!
        assertThat(loaded.movements).isEqualTo(cashed.movements)
        assertThat(loaded.balance).isEqualTo(Money.ZERO)
        assertThat(accounts.projection(f.day.id, f.entry.playerId)).isEqualTo(AccountProjection.from(loaded))
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO account_movement(race_day_id,player_id,movement_key,movement_type,amount,coupon_id,created_at) VALUES (?,?,?,'COUPON_STAKE',?,?,now())",
                f.day.id.value,
                f.entry.playerId.value,
                p.debit.key,
                p.debit.amount.amount,
                p.coupon.id.value
            )
        }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
    }

    @Test
    fun poolAggregationAndVersions() {
        val f = fixture();
        val p = purchase(f); save(f, p);
        val q = purchase(f); save(f, q)
        val key = p.poolKey
        val snapshot = PoolSnapshot.aggregate(key, listOf(p.coupon, q.coupon), version = 1).getOrThrow()
        pools.update(f.day.id, 0, snapshot)
        val loaded = pools.load(f.day.id, key)!!
        assertThat(loaded.units).isEqualTo(snapshot.units); assertThat(loaded.validStake).isEqualTo(snapshot.validStake)
        assertThat(pools.list(f.day.id, f.race.id)).hasSize(4)
        assertThatThrownBy { pools.update(f.day.id, 0, snapshot) }.isInstanceOf(StaleVersionException::class.java)
        assertThatThrownBy {
            jdbc.update(
                "UPDATE pool_state SET valid_stake=-1 WHERE race_id=? AND bet_type=?",
                f.race.id.value,
                BetType.WIN.name
            )
        }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
    }

    @Test
    fun revisionsSettlementsPayoutsAndTerminalResolutions() {
        val f = fixture();
        val p = purchase(f, BetType.WIN, CouponSelection.Box(f.horses)); save(f, p)
        val proposed = RaceResult(f.race.id, "r1", f.horses.take(3), ResultStatus.PROPOSED)
        results.insert(f.day.id, proposed)
        results.insert(f.day.id, RaceResult(f.race.id, "r2", f.horses.take(3), ResultStatus.PROPOSED))
        assertThat(results.list(f.day.id, f.race.id).map { it.revision }).containsExactly("r1", "r2")
        results.verify(f.day.id, f.race.id, "r2")
        val result = results.load(f.day.id, f.race.id, "r2")!!
        val state = RaceState(f.race.id, RacePhase.RESULT_VERIFIED, result = result, version = 1)
        races.update(f.day.id, 0, state)
        assertThat(races.state(f.day.id, f.race.id)!!.result!!.finishers).isEqualTo(result.finishers)
        val snapshots = BetType.entries.associate {
            val key = PoolKey(f.race.id, it); key to PoolSnapshot.aggregate(
            key,
            listOf(p.coupon)
        ).getOrThrow()
        }
        val plan =
            pl.umamusume.tiket.core.settlement.RaceSettlementPlanner.plan(
                SettlementId(UUID.randomUUID()),
                f.race,
                state,
                BetType.entries.associateWith { f.day.pools.getValue(PoolKey(f.race.id, it)) },
                snapshots,
                listOf(p.coupon)
            ).getOrThrow()
        tx.required {
            settlements.insert(
                f.day.id,
                StoredSettlement(plan.id, f.race.id, plan.resultRevision, plan.pools, plan.expectedPoolVersions)
            )
            plan.resolutions.forEach { resolutions.insert(f.day.id, it) }
            plan.credits.forEach { credit ->
                val a = accounts.load(f.day.id, credit.playerId)!!
                accounts.append(a.version, AccountProjector.apply(a, credit.movement).getOrThrow())
                settlements.insertPayout(
                    f.day.id,
                    StoredPayout(
                        plan.id,
                        credit.playerId,
                        credit.movement.legId!!,
                        credit.movement.amount,
                        credit.movement.key
                    )
                )
            }; Unit
        }
        assertThat(settlements.load(f.day.id, plan.id)!!.pools).isEqualTo(plan.pools)
        assertThat(settlements.payouts(f.day.id, plan.id)).hasSize(1)
        assertThatThrownBy {
            settlements.insertPayout(
                f.day.id,
                settlements.payouts(f.day.id, plan.id).single()
            )
        }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThat(plan.resolutions.map { it.outcome }).contains(LegOutcome.WON, LegOutcome.LOST)
        assertThat(
            accounts.load(
                f.day.id,
                f.entry.playerId
            )!!.winnings
        ).isEqualTo(plan.playerPayouts.getValue(f.entry.playerId))
        assertThat(resolutions.list(f.day.id, f.race.id).values.toList()).isEqualTo(plan.resolutions)
        assertThatThrownBy {
            resolutions.insert(
                f.day.id,
                plan.resolutions[0]
            )
        }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThatThrownBy {
            settlements.insert(
                f.day.id,
                StoredSettlement(
                    SettlementId(UUID.randomUUID()),
                    f.race.id,
                    plan.resultRevision,
                    plan.pools,
                    plan.expectedPoolVersions
                )
            )
        }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
    }

    /** Both threads enter separate Spring transactions/connections before either attempts mutation. */
    fun compete(work: (Int) -> Unit): List<Throwable?> {
        val barrier = CyclicBarrier(2);
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (0..1).map { i ->
                executor.submit<Throwable?> {
                    try {
                        tx.required {
                            jdbc.queryForObject("SELECT 1", Int::class.java); barrier.await(
                            15,
                            TimeUnit.SECONDS
                        ); work(i); Unit
                        }; null
                    } catch (t: Throwable) {
                        t
                    }
                }
            }
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentAccountsRejectStaleUpdate() {
        val f = fixture();
        val p = purchase(f);
        val q = purchase(f)
        val a = accounts.load(f.day.id, f.entry.playerId)!!
        val snapshots = listOf(p, q).map { AccountProjector.apply(a, it.debit).getOrThrow() }
        val outcome = compete { i ->
            coupons.insert(
                listOf(p, q)[i].coupon,
                PurchaseMetadata(1, "c"),
                f.race.roster
            ); accounts.append(0, snapshots[i])
        }
        assertThat(outcome.count { it == null }).isEqualTo(1)
        assertThat(outcome.filterNotNull().single()).isInstanceOf(StaleVersionException::class.java)
        assertThat(accounts.load(f.day.id, f.entry.playerId)!!.movements).hasSize(1)
        assertThat(coupons.list(f.day.id, f.race.id)).hasSize(1)
    }

    @Test
    fun concurrentPoolsRejectStaleUpdate() {
        val f = fixture();
        val p = purchase(f)
        val snapshot = PoolSnapshot.aggregate(p.poolKey, listOf(p.coupon), version = 1).getOrThrow()
        val outcome = compete { pools.update(f.day.id, 0, snapshot) }
        assertThat(outcome.count { it == null }).isEqualTo(1)
        assertThat(outcome.filterNotNull().single()).isInstanceOf(StaleVersionException::class.java)
    }

    @Test
    fun concurrentCouponIdentityIsUnique() {
        val f = fixture();
        val p = purchase(f)
        val outcome = compete { coupons.insert(p.coupon, PurchaseMetadata(1, "c"), f.race.roster) }
        assertThat(outcome.count { it == null }).isEqualTo(1)
        assertThat(coupons.list(f.day.id, f.race.id)).hasSize(1)
    }

    @Test
    fun concurrentIdempotencyReplaysAndRollbackReleasesKey() {
        val f = fixture();
        val p = purchase(f);
        val key = CommandKey("purchase", f.day.id.value.toString(), "key")
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val outcome = compete {
            assertThat(idempotency.execute(key, "fingerprint") {
                calls.incrementAndGet(); save(
                f,
                p
            ); p.coupon.id.value.toString()
            }).isEqualTo(p.coupon.id.value.toString())
        }
        assertThat(outcome).containsOnlyNulls(); assertThat(calls.get()).isEqualTo(1)
        assertThatThrownBy {
            idempotency.execute(
                key,
                "different"
            ) { "bad" }
        }.isInstanceOf(IdempotencyConflictException::class.java)
        val failed = key.copy(key = "rollback")
        assertThatThrownBy {
            idempotency.execute(failed, "f") {
                val q = purchase(f); save(
                f,
                q
            ); error("deliberate failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(idempotency.load(failed)).isNull()
        assertThat(coupons.list(f.day.id, f.race.id)).hasSize(1)
        assertThat(idempotency.execute(failed, "f") { "retry" }).isEqualTo("retry")
    }

    @Test
    fun rollbackRemovesCouponAndFinancialWrites() {
        val f = fixture();
        val p = purchase(f)
        assertThatThrownBy {
            tx.required {
                save(f, p); pools.update(
                f.day.id,
                0,
                PoolSnapshot.aggregate(p.poolKey, listOf(p.coupon), version = 1).getOrThrow()
            ); error("deliberate")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(coupons.load(f.day.id, p.coupon.id)).isNull()
        assertThat(accounts.movements(f.day.id, f.entry.playerId)).isEmpty()
        assertThat(accounts.load(f.day.id, f.entry.playerId)!!.balance).isEqualTo(f.entry.openingBalance)
        assertThat(pools.load(f.day.id, p.poolKey)!!.units).isEmpty()
    }

    @Test
    fun migrationsUpgradeValidateAndDetectMissingHistory() {
        val schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "")
        val v1 = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).schemas(schema)
            .target("1").load()
        v1.migrate(); assertThat(v1.info().applied().filter { it.version != null }).hasSize(1)
        val all =
            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).schemas(schema).load()
        all.migrate(); all.validate(); assertThat(all.info().applied().filter { it.version != null }).hasSize(4)
        assertThat(all.migrate().migrationsExecuted).isZero()
        val missing =
            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).schemas(schema)
                .locations("classpath:nonexistent").ignoreMigrationPatterns("").load()
        assertThatThrownBy { missing.validate() }.isInstanceOf(org.flywaydb.core.api.FlywayException::class.java)
    }

    @Test
    fun corruptedCouponAndProjectionFailClearly() {
        val f = fixture();
        val p = purchase(f); save(f, p)
        assertThatThrownBy {
            tx.required {
                // Test-only superuser bypass simulates disk/import corruption; transaction rolls back.
                jdbc.execute("SET LOCAL session_replication_role=replica")
                jdbc.update("UPDATE coupon SET total_stake=total_stake+1 WHERE id=?", p.coupon.id.value)
                assertThatThrownBy {
                    coupons.load(
                        f.day.id,
                        p.coupon.id
                    )
                }.isInstanceOf(pl.umamusume.tiket.core.validation.DomainException::class.java)
                error("rollback corruption")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            tx.required {
                jdbc.update(
                    "UPDATE account_balance SET balance=balance+1 WHERE race_day_id=? AND player_id=?",
                    f.day.id.value,
                    f.entry.playerId.value
                )
                assertThatThrownBy {
                    accounts.load(
                        f.day.id,
                        f.entry.playerId
                    )
                }.isInstanceOf(pl.umamusume.tiket.core.validation.DomainException::class.java)
                error("rollback projection corruption")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(coupons.load(f.day.id, p.coupon.id)!!.coupon.totalStake).isEqualTo(p.coupon.totalStake)
    }

    @Test
    fun refundResolutionIsTerminalAndRaceVersionIsChecked() {
        val f = fixture();
        val p = purchase(f); save(f, p)
        val leg = p.coupon.legs.single()
        val resolution = LegResolution(leg.id, LegOutcome.REFUNDED, leg.stake)
        resolutions.insert(f.day.id, resolution)
        assertThat(resolutions.list(f.day.id, f.race.id).getValue(leg.id)).isEqualTo(resolution)
        assertThatThrownBy {
            resolutions.insert(
                f.day.id,
                resolution
            )
        }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        races.update(f.day.id, 0, RaceState(f.race.id, version = 1))
        assertThatThrownBy { races.update(f.day.id, 0, RaceState(f.race.id, version = 1)) }.isInstanceOf(
            StaleVersionException::class.java
        )
        val before = accounts.load(f.day.id, f.entry.playerId)!!
        val after = AccountProjector.apply(
            before,
            AccountMovement("refund", MovementType.LEG_REFUND, leg.stake, p.coupon.id, leg.id)
        ).getOrThrow()
        accounts.append(before.version, after)
        assertThatThrownBy { accounts.append(before.version, after) }.isInstanceOf(StaleVersionException::class.java)
    }

    @Test
    fun openingFundingRejectsExistingFinancialActivity() {
        val f = fixture()
        save(f, purchase(f))
        val service = context.getBean(pl.umamusume.tiket.backend.application.EnrollmentApplicationService::class.java)
        assertThatThrownBy {
            service.fund(pl.umamusume.tiket.contracts.v1.AddOpeningFundingRequest.newBuilder()
                .setRaceDayId(f.day.id.value.toString()).setPlayerId(f.entry.playerId.value.toString())
                .setExpectedAccountVersion(1).setAdditionalPaid(1).setIdempotencyKey("activity").build())
        }.isInstanceOf(pl.umamusume.tiket.backend.application.CommandPrecondition::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM opening_funding_adjustment WHERE race_day_id=?",
            Long::class.java, f.day.id.value)).isZero()
    }

    @Test
    fun v2StoredDataBackfillsRaceOrder() {
        val schema = "v2_" + UUID.randomUUID().toString().replace("-", "")
        val migration = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema).target("2").load()
        migration.migrate()
        java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET search_path TO " + schema)
                val day = UUID.randomUUID()
                statement.execute("INSERT INTO race_day VALUES ('$day','2026-11-10',42,'PLN','Europe/Warsaw','v1',10000,1,'HOUSE_RETAINS_POOL')")
                statement.execute("INSERT INTO race_day_state VALUES ('$day','DRAFT',0)")
                listOf(9,2).forEach { number ->
                    statement.execute("INSERT INTO race VALUES ('${UUID.randomUUID()}','$day',$number,'Race','Course')")
                }
                val player=UUID.randomUUID()
                statement.execute("INSERT INTO player_entry VALUES ('$day','$player',10,20)")
                statement.execute("INSERT INTO account_balance VALUES ('$day','$player',30,0,0,0,0,false)")
            }
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).schemas(schema).load().migrate()
        java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET search_path TO " + schema)
                statement.executeQuery("SELECT number,ordinal FROM race ORDER BY ordinal").use { rows ->
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2); assertThat(rows.getInt(2)).isZero()
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(9); assertThat(rows.getInt(2)).isEqualTo(1)
                }
                statement.executeQuery("SELECT opening_version,balance FROM account_balance").use { rows ->
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero(); assertThat(rows.getLong(2)).isEqualTo(30)
                }
            }
        }
    }

    @Test
    fun incompleteIdempotencyReservationCannotCommit() {
        val key = CommandKey("test", "scope", UUID.randomUUID().toString())
        assertThatThrownBy {
            tx.required {
                jdbc.update(
                    "INSERT INTO command_idempotency(operation,scope,idempotency_key,fingerprint) VALUES (?,?,?,'f')",
                    key.operation,
                    key.scope,
                    key.key
                )
                Unit
            }
        }.isInstanceOf(org.springframework.transaction.TransactionSystemException::class.java)
        assertThat(idempotency.load(key)).isNull()
        assertThat(idempotency.execute(key, "f") { "completed" }).isEqualTo("completed")
    }
}
