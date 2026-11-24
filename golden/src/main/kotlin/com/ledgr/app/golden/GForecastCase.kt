package com.ledgr.app.golden

import com.ledgr.app.domain.forecast.Forecast
import com.ledgr.app.domain.forecast.Forecaster
import com.ledgr.app.domain.forecast.RecurringDetector
import com.ledgr.app.domain.forecast.RecurringSeries
import com.ledgr.app.domain.forecast.RecurringStatus
import com.ledgr.app.domain.forecast.SpendEvent
import com.ledgr.app.domain.forecast.SpendEvents
import com.ledgr.app.domain.ledger.Aggregations
import com.ledgr.app.domain.ledger.DailyFact
import com.ledgr.app.domain.ledger.LedgerRules
import com.ledgr.app.domain.ledger.LedgerView
import com.ledgr.app.domain.ledger.Period
import com.ledgr.app.domain.ledger.PeriodKind
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.ledger.TxnStatus
import java.io.File
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * G-FORECAST (docs/07 §4, docs/12 §4): a synthetic 6-month history (rent, two subscriptions, an EMI, a salary, an own
 * transfer, a linked refund, one outlier) expanded in code from `forecast/history.json` with a fixed-seed generator.
 *
 * Pins: the recurring items detected exactly; the day-15 projection against the real month total (within 8 %, and the
 * P25–P75 range containing it); a weekly forecast; a short-history forecast (Low confidence, overall median); a
 * dismissed series; and that KPI, daily bars, donut, merchants and table footer add up to the same totals for three
 * views (docs/07: "totals agree").
 */
object GForecastCase : GoldenCase {
    override val id = "G-FORECAST"
    override val expectedFile = "g-forecast.json"

    @Serializable
    private data class Discretionary(val counterpartyId: Long, val categoryId: Long, val label: String)

    @Serializable
    private data class RecurringSpec(
        val counterpartyId: Long,
        val categoryId: Long,
        val accountId: Long,
        val label: String,
        val amount: Long,
        val dayOfMonth: Int,
    )

    @Serializable
    private data class Outlier(val date: String, val counterpartyId: Long, val categoryId: Long, val label: String, val amount: Long)

    @Serializable
    private data class IncomeSpec(val counterpartyId: Long, val label: String, val amount: Long, val dayOfMonth: Int)

    @Serializable
    private data class TransferSpec(val date: String, val amount: Long)

    @Serializable
    private data class RefundSpec(val date: String, val counterpartyId: Long, val categoryId: Long, val amount: Long)

    @Serializable
    private data class Fixture(
        val start: String,
        val end: String,
        val today: String,
        val weekToday: String,
        val shortToday: String,
        val seed: Long,
        val noisePct: Int,
        val weekdayBase: List<Long>,
        val discretionary: List<Discretionary>,
        val recurring: List<RecurringSpec>,
        val outliers: List<Outlier>,
        val income: List<IncomeSpec>,
        val selfTransfers: List<TransferSpec>,
        val refunds: List<RefundSpec>,
    )

    @Serializable
    private data class SeriesOut(
        val key: String,
        val label: String,
        val cadence: String,
        val amount: Long,
        val intervalDays: Int,
        val last: String,
        val next: String,
        val occurrences: Int,
    )

    @Serializable
    private data class ExpectedOut(val label: String, val date: String, val amount: Long)

    @Serializable
    private data class ForecastOut(
        val today: String,
        val periodStart: String,
        val periodEnd: String,
        val spentSoFar: Long,
        val recurringTotal: Long,
        val recurring: List<ExpectedOut>,
        val remainingDays: Int,
        val discretionary: Long,
        val perDay: Long,
        val expected: Long,
        val low: Long,
        val high: Long,
        val confidence: String,
        val historyDays: Int,
        val usedOverallMedian: Boolean,
        val pathPoints: Int,
    )

    @Serializable
    private data class Agreement(
        val view: String,
        val spent: Long,
        val income: Long,
        val sumDailyBars: Long,
        val sumCategorySlices: Long,
        val sumMerchants: Long,
        val footerSpent: Long,
        val footerIncome: Long,
        val consistent: Boolean,
    )

    @Serializable
    private data class Output(
        val recurringDetected: List<SeriesOut>,
        val recurringExact: Boolean,
        val recurringAfterDismissingRent: List<String>,
        val monthForecastOnDay15: ForecastOut,
        val actualMonthTotal: Long,
        /** Signed error of the projection in basis points: (expected - actual) * 10000 / actual. */
        val errorBasisPoints: Long,
        val withinEightPercent: Boolean,
        val rangeContainsActual: Boolean,
        val weekForecast: ForecastOut,
        val shortHistoryForecast: ForecastOut,
        val agreement: List<Agreement>,
    )

    override fun run(context: GoldenContext): String {
        val fx = GoldenJson.json.decodeFromString(
            Fixture.serializer(),
            File(context.fixturesDir, "forecast/history.json").readText(Charsets.UTF_8),
        )
        val labels = HashMap<Long, String>()
        fx.discretionary.forEach { labels[it.counterpartyId] = it.label }
        fx.recurring.forEach { labels[it.counterpartyId] = it.label }
        fx.outliers.forEach { labels[it.counterpartyId] = it.label }
        val facts = expand(fx)
        val combinedOff = LedgerView.Combined(includeOwnTransfers = false)

        fun upTo(date: LocalDate) = facts.filter { !it.localDate.isAfter(date) }
        fun events(list: List<DailyFact>): List<SpendEvent> = list.mapNotNull {
            SpendEvents.of(it, labels[it.counterpartyId].orEmpty(), labels[it.counterpartyId].orEmpty(), combinedOff)
        }
        fun forecastFor(today: LocalDate, period: Period, series: List<RecurringSeries>): Forecast {
            val daily = LedgerRules.daily(upTo(today), combinedOff).mapValues { it.value.spend }
            return Forecaster.forecast(daily, series, today, period.start, period.end)
                ?: error("no forecast for $today")
        }

        // Day 15 of the last month: detect, forecast, compare with what really happened.
        val today = LocalDate.parse(fx.today)
        val month = Period.of(PeriodKind.MONTH, today)
        val detected = RecurringDetector.detect(events(upTo(today)), today)
        val expectedSeries = fx.recurring.map { "cp:${it.counterpartyId}" to it.amount }.toSet()
        val exact = detected.size == fx.recurring.size && detected.map { it.key to it.amount }.toSet() == expectedSeries
        val dismissed = RecurringDetector.detect(
            events(upTo(today)), today, mapOf("cp:${fx.recurring.first().counterpartyId}" to RecurringStatus.DISMISSED),
        ).map { it.label }

        val forecast = forecastFor(today, month, detected)
        val actual = Aggregations.kpis(facts, combinedOff, month).spent
        val errorBp = (forecast.expected - actual) * 10_000 / actual
        val within = kotlin.math.abs(forecast.expected - actual) * 100 <= actual * 8

        // A weekly forecast, with the weekly series detected as of that day.
        val weekToday = LocalDate.parse(fx.weekToday)
        val week = Period.of(PeriodKind.WEEK, weekToday)
        val weekSeries = RecurringDetector.detect(events(upTo(weekToday)), weekToday)
        val weekForecast = forecastFor(weekToday, week, weekSeries)

        // Under 30 days of history: Low confidence, overall daily median instead of weekday rates.
        val shortToday = LocalDate.parse(fx.shortToday)
        val shortForecast = forecastFor(shortToday, Period.of(PeriodKind.MONTH, shortToday), emptyList())

        val agreement = listOf(
            agree("combined, own transfers off", facts, combinedOff, month),
            agree("combined, own transfers on", facts, LedgerView.Combined(includeOwnTransfers = true), month),
            agree("account 1 on its own", facts.filter { it.accountId == 1L }, LedgerView.PerAccount, month),
        )

        val out = Output(
            recurringDetected = detected.map {
                SeriesOut(it.key, it.label, it.cadence.name, it.amount, it.intervalDays, it.last.toString(), it.next.toString(), it.occurrences)
            },
            recurringExact = exact,
            recurringAfterDismissingRent = dismissed,
            monthForecastOnDay15 = forecast.toOut(),
            actualMonthTotal = actual,
            errorBasisPoints = errorBp,
            withinEightPercent = within,
            rangeContainsActual = actual in forecast.low..forecast.high,
            weekForecast = weekForecast.toOut(),
            shortHistoryForecast = shortForecast.toOut(),
            agreement = agreement,
        )
        return GoldenJson.finish(GoldenJson.json.encodeToString(Output.serializer(), out))
    }

    private fun Forecast.toOut() = ForecastOut(
        today = today.toString(),
        periodStart = start.toString(),
        periodEnd = end.toString(),
        spentSoFar = spentSoFar,
        recurringTotal = recurringTotal,
        recurring = recurring.map { ExpectedOut(it.label, it.date.toString(), it.amount) },
        remainingDays = remainingDays,
        discretionary = discretionary,
        perDay = perDay,
        expected = expected,
        low = low,
        high = high,
        confidence = confidence.name,
        historyDays = historyDays,
        usedOverallMedian = usedOverallMedian,
        pathPoints = path.size,
    )

    private fun agree(name: String, facts: List<DailyFact>, view: LedgerView, period: Period): Agreement {
        val inRange = Aggregations.inPeriod(facts, period)
        val kpi = Aggregations.kpis(facts, view, period)
        val bars = Aggregations.dailySpend(facts, view, period).sum()
        val slices = Aggregations.categorySlices(facts, view, period).sumOf { it.spend }
        val merchants = LedgerRules.byCounterparty(inRange, view).values.sumOf { it.spend }
        val footer = LedgerRules.daily(inRange, view).values.fold(0L to 0L) { acc, c -> (acc.first + c.spend) to (acc.second + c.income) }
        return Agreement(
            view = name,
            spent = kpi.spent,
            income = kpi.income,
            sumDailyBars = bars,
            sumCategorySlices = slices,
            sumMerchants = merchants,
            footerSpent = footer.first,
            footerIncome = footer.second,
            consistent = kpi.spent == bars && kpi.spent == slices && kpi.spent == merchants &&
                kpi.spent == footer.first && kpi.income == footer.second,
        )
    }

    /** Expands the compact spec into one fact per transaction. A fixed-seed LCG makes it identical on every machine. */
    private fun expand(fx: Fixture): List<DailyFact> {
        val out = ArrayList<DailyFact>()
        var state = fx.seed
        fun next(bound: Int): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) % bound).toInt()
        }

        fun fact(date: LocalDate, account: Long, category: Long?, counterparty: Long?, kind: TxnKind, amount: Long, linked: Boolean = false) {
            out += DailyFact(date, account, category, counterparty, kind, TxnStatus.ACTIVE, linked, amount)
        }

        var date = LocalDate.parse(fx.start)
        val end = LocalDate.parse(fx.end)
        while (!date.isAfter(end)) {
            // Everyday spending: one payment, weekday-shaped, ±noisePct around the base.
            val merchant = fx.discretionary[next(fx.discretionary.size)]
            val noise = next(2 * fx.noisePct + 1) - fx.noisePct
            val base = fx.weekdayBase[date.dayOfWeek.ordinal]
            fact(date, 2L, merchant.categoryId, merchant.counterpartyId, TxnKind.EXPENSE, base * (100 + noise) / 100)

            for (r in fx.recurring) if (date.dayOfMonth == r.dayOfMonth) {
                fact(date, r.accountId, r.categoryId, r.counterpartyId, TxnKind.EXPENSE, r.amount)
            }
            for (i in fx.income) if (date.dayOfMonth == i.dayOfMonth) {
                fact(date, 1L, null, i.counterpartyId, TxnKind.INCOME, i.amount)
            }
            for (o in fx.outliers) if (date == LocalDate.parse(o.date)) {
                fact(date, 2L, o.categoryId, o.counterpartyId, TxnKind.EXPENSE, o.amount)
            }
            for (t in fx.selfTransfers) if (date == LocalDate.parse(t.date)) {
                fact(date, 1L, null, null, TxnKind.SELF_TRANSFER_OUT, t.amount)
                fact(date, 2L, null, null, TxnKind.SELF_TRANSFER_IN, t.amount)
            }
            for (rf in fx.refunds) if (date == LocalDate.parse(rf.date)) {
                fact(date, 2L, rf.categoryId, rf.counterpartyId, TxnKind.REFUND, rf.amount, linked = true)
            }
            date = date.plusDays(1)
        }
        return out
    }
}
