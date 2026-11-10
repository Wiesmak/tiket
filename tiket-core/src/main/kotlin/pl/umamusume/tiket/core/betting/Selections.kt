package pl.umamusume.tiket.core.betting

import java.math.BigInteger
import pl.umamusume.tiket.core.identity.*
import pl.umamusume.tiket.core.validation.*

enum class BetType(val positions: Int) { WIN(1), QUINELLA(2), EXACTA(2), TRIFECTA(3) }

sealed class AtomicBet(val type: BetType, horses: List<HorseId>) {
    val horses = frozen(horses)
    init { ensure(horses.distinct().size == horses.size, ErrorCode.DUPLICATE_HORSE, "atomic horses repeat") }
    class Win(horse: HorseId) : AtomicBet(BetType.WIN, listOf(horse))
    class Quinella(a: HorseId, b: HorseId) : AtomicBet(BetType.QUINELLA, listOf(a,b).sortedWith(horseOrder))
    class Exacta(first: HorseId, second: HorseId) : AtomicBet(BetType.EXACTA, listOf(first,second))
    class Trifecta(first: HorseId, second: HorseId, third: HorseId) : AtomicBet(BetType.TRIFECTA, listOf(first,second,third))
    val canonical: String get() = type.name + ":" + horses.joinToString("/") { it.value.toString() }
    final override fun equals(other: Any?) = other is AtomicBet && type == other.type && horses == other.horses
    final override fun hashCode() = 31 * type.hashCode() + horses.hashCode()
    final override fun toString() = canonical
    companion object {
        fun create(type: BetType, horses: List<HorseId>): Decision<AtomicBet> = decide {
            ensure(horses.size == type.positions, ErrorCode.INVALID_SELECTION, "wrong atomic arity")
            when(type) {
                BetType.WIN -> Win(horses[0])
                BetType.QUINELLA -> Quinella(horses[0], horses[1])
                BetType.EXACTA -> Exacta(horses[0], horses[1])
                BetType.TRIFECTA -> Trifecta(horses[0], horses[1], horses[2])
            }
        }
    }
}
sealed interface PositionChoice {
    data class Horse(val id: HorseId) : PositionChoice
    data object Wall : PositionChoice
}
sealed class CouponSelection {
    class Positional(positions: List<PositionChoice>) : CouponSelection() {
        val positions = frozen(positions)
        override fun equals(other: Any?) = other is Positional && positions == other.positions
        override fun hashCode() = positions.hashCode()
    }
    class Box(horses: List<HorseId>) : CouponSelection() {
        val horses = frozen(horses.sortedWith(horseOrder))
        override fun equals(other: Any?) = other is Box && horses == other.horses
        override fun hashCode() = horses.hashCode()
    }
}
class ExpansionResult internal constructor(val selection: CouponSelection, bets: List<AtomicBet>) {
    val bets = frozen(bets)
    val combinations: Int get() = bets.size
}
object SelectionExpander {
    fun expand(type: BetType, selection: CouponSelection, roster: Set<HorseId>,
               eligible: Set<HorseId> = roster, maximumLegs: Int = 10000): Decision<ExpansionResult> = decide {
        ensure(maximumLegs > 0 && roster.containsAll(eligible), ErrorCode.INVALID_DEFINITION, "invalid expansion context")
        val explicit = when(selection) {
            is CouponSelection.Box -> selection.horses
            is CouponSelection.Positional -> selection.positions.filterIsInstance<PositionChoice.Horse>().map { it.id }
        }
        ensure(explicit.distinct().size == explicit.size, ErrorCode.DUPLICATE_HORSE, "explicit horses repeat")
        explicit.forEach {
            ensure(it in roster, ErrorCode.UNKNOWN_HORSE, it.toString())
            ensure(it in eligible, ErrorCode.SCRATCHED_HORSE, it.toString())
        }
        var count = BigInteger.ONE
        when(selection) {
            is CouponSelection.Box -> {
                ensure(explicit.size >= maxOf(2, type.positions), ErrorCode.INVALID_SELECTION, "box too small")
                repeat(type.positions) { count = count.multiply(BigInteger.valueOf((explicit.size - it).toLong())) }
                if (type == BetType.QUINELLA) count = count.divide(BigInteger.TWO)
            }
            is CouponSelection.Positional -> {
                ensure(selection.positions.size == type.positions && explicit.isNotEmpty(),
                    ErrorCode.INVALID_SELECTION, "wrong arity or wall-only selection")
                repeat(type.positions - explicit.size) {
                    count = count.multiply(BigInteger.valueOf((eligible.size - explicit.size - it).coerceAtLeast(0).toLong()))
                }
            }
        }
        ensure(count.signum() > 0, ErrorCode.EMPTY_EXPANSION, "no valid combinations")
        ensure(count <= BigInteger.valueOf(maximumLegs.toLong()), ErrorCode.EXPANSION_LIMIT, "combinations=$count limit=$maximumLegs")
        val candidates = when(selection) {
            is CouponSelection.Box -> List(type.positions) { explicit }
            is CouponSelection.Positional -> selection.positions.map {
                when(it) { is PositionChoice.Horse -> listOf(it.id); PositionChoice.Wall -> eligible.sortedWith(horseOrder) }
            }
        }
        val bets = sortedMapOf<String, AtomicBet>()
        fun walk(prefix: List<HorseId>) {
            if (prefix.size == type.positions) {
                val bet = AtomicBet.create(type, prefix).getOrThrow()
                bets[bet.canonical] = bet
            } else candidates[prefix.size].forEach { if (it !in prefix) walk(prefix + it) }
        }
        walk(emptyList())
        ensure(bets.isNotEmpty(), ErrorCode.EMPTY_EXPANSION, "no valid combinations")
        // A reversed positional Quinella is the same canonical original expression.
        val canonicalSelection = if (selection is CouponSelection.Positional && type == BetType.QUINELLA)
            CouponSelection.Positional(selection.positions.sortedBy {
                when(it) { is PositionChoice.Horse -> it.id.value.toString(); PositionChoice.Wall -> "~" }
            }) else selection
        ExpansionResult(canonicalSelection, bets.values.toList())
    }
}
