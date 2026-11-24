package com.ledgr.app.ui.icons

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.ledgr.app.R

/** One pickable icon: the key stored in `category.icon`, a spoken name, and its drawing. */
data class IconOption(
    val key: String,
    @StringRes val label: Int,
    val vector: ImageVector,
)

/**
 * The category icon set (the keys used in `categories.yaml`), hand-drawn as 2 dp round strokes on a 24 dp grid
 * so it matches the other outlined icons and needs no icon library. Unknown keys fall back to [FALLBACK].
 */
object CategoryIcons {

    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
    }

    private fun PathBuilder.box(left: Float, top: Float, right: Float, bottom: Float) {
        moveTo(left, top); lineTo(right, top); lineTo(right, bottom); lineTo(left, bottom); close()
    }

    private val restaurant = icon("restaurant") {
        moveTo(5f, 3f); lineTo(5f, 8f); curveTo(5f, 10.5f, 6.5f, 11f, 8f, 11f)
        curveTo(9.5f, 11f, 11f, 10.5f, 11f, 8f); lineTo(11f, 3f)
        moveTo(8f, 3f); lineTo(8f, 21f)
        moveTo(18f, 21f); lineTo(18f, 3f); curveTo(15.5f, 5f, 15f, 9f, 15f, 12f); lineTo(18f, 12f)
    }

    private val grocery = icon("grocery") {
        moveTo(3f, 4f); lineTo(5.5f, 4f); lineTo(8f, 15f); lineTo(18f, 15f); lineTo(20f, 8f); lineTo(6.3f, 8f)
        circle(9.5f, 19f, 1.4f)
        circle(16.5f, 19f, 1.4f)
    }

    private val shopping = icon("shopping") {
        box(5f, 8f, 19f, 21f)
        moveTo(9f, 8f); lineTo(9f, 6.5f); curveTo(9f, 3.5f, 15f, 3.5f, 15f, 6.5f); lineTo(15f, 8f)
    }

    private val transport = icon("transport") {
        moveTo(3f, 16f); lineTo(3f, 11f); lineTo(5.5f, 6f); lineTo(18.5f, 6f); lineTo(21f, 11f); lineTo(21f, 16f); close()
        moveTo(3f, 11f); lineTo(21f, 11f)
        moveTo(6.5f, 16f); lineTo(6.5f, 19f)
        moveTo(17.5f, 16f); lineTo(17.5f, 19f)
    }

    private val fuel = icon("fuel") {
        moveTo(5f, 21f); lineTo(5f, 4f); lineTo(14f, 4f); lineTo(14f, 21f)
        moveTo(3f, 21f); lineTo(16f, 21f)
        moveTo(5f, 10f); lineTo(14f, 10f)
        moveTo(14f, 8f); lineTo(17f, 8f); lineTo(17f, 17f); curveTo(17f, 19f, 20f, 19f, 20f, 17f); lineTo(20f, 8f); lineTo(18f, 6f)
    }

    private val bill = icon("bill") {
        moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(15f, 19f); lineTo(12f, 21f); lineTo(9f, 19f); lineTo(6f, 21f); close()
        moveTo(9f, 8f); lineTo(15f, 8f)
        moveTo(9f, 12f); lineTo(15f, 12f)
    }

    private val phone = icon("phone") {
        box(7.5f, 3f, 16.5f, 21f)
        moveTo(11f, 18f); lineTo(13f, 18f)
    }

    private val home = icon("home") {
        moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f)
        moveTo(5f, 9.5f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 9.5f)
        moveTo(10f, 21f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 21f)
    }

    private val loan = icon("loan") {
        moveTo(3f, 9f); lineTo(12f, 4f); lineTo(21f, 9f); close()
        moveTo(6f, 11.5f); lineTo(6f, 17f)
        moveTo(10f, 11.5f); lineTo(10f, 17f)
        moveTo(14f, 11.5f); lineTo(14f, 17f)
        moveTo(18f, 11.5f); lineTo(18f, 17f)
        moveTo(4f, 20f); lineTo(20f, 20f)
    }

    private val investment = icon("investment") {
        moveTo(3f, 17f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(21f, 7f)
        moveTo(15f, 7f); lineTo(21f, 7f); lineTo(21f, 13f)
    }

    private val shield = icon("shield") {
        moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 12f); curveTo(20f, 17f, 16f, 20f, 12f, 21.5f)
        curveTo(8f, 20f, 4f, 17f, 4f, 12f); lineTo(4f, 6f); close()
        moveTo(9f, 12f); lineTo(11f, 14f); lineTo(15f, 10f)
    }

    private val health = icon("health") {
        moveTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 9f); lineTo(20f, 9f); lineTo(20f, 15f); lineTo(15f, 15f)
        lineTo(15f, 20f); lineTo(9f, 20f); lineTo(9f, 15f); lineTo(4f, 15f); lineTo(4f, 9f); lineTo(9f, 9f); close()
    }

    private val entertainment = icon("entertainment") {
        box(3f, 5f, 21f, 19f)
        moveTo(10f, 9f); lineTo(15f, 12f); lineTo(10f, 15f); close()
    }

    private val travel = icon("travel") {
        box(4f, 8f, 20f, 20f)
        moveTo(9f, 8f); lineTo(9f, 5f); lineTo(15f, 5f); lineTo(15f, 8f)
        moveTo(4f, 13f); lineTo(20f, 13f)
    }

    private val education = icon("education") {
        moveTo(2f, 9f); lineTo(12f, 4f); lineTo(22f, 9f); lineTo(12f, 14f); close()
        moveTo(6f, 11.5f); lineTo(6f, 16f); curveTo(6f, 19f, 18f, 19f, 18f, 16f); lineTo(18f, 11.5f)
        moveTo(22f, 9f); lineTo(22f, 15f)
    }

    private val personal = icon("personal") {
        moveTo(12f, 3f); lineTo(14f, 10f); lineTo(21f, 12f); lineTo(14f, 14f); lineTo(12f, 21f)
        lineTo(10f, 14f); lineTo(3f, 12f); lineTo(10f, 10f); close()
    }

    private val gift = icon("gift") {
        box(4f, 10f, 20f, 21f)
        box(3f, 7f, 21f, 10f)
        moveTo(12f, 7f); lineTo(12f, 21f)
        moveTo(12f, 7f); curveTo(9f, 7f, 7f, 4.5f, 9f, 3.5f); curveTo(10.5f, 3f, 12f, 5f, 12f, 7f)
        curveTo(15f, 7f, 17f, 4.5f, 15f, 3.5f); curveTo(13.5f, 3f, 12f, 5f, 12f, 7f)
    }

    private val cash = icon("cash") {
        box(2f, 6f, 22f, 18f)
        circle(12f, 12f, 3f)
        moveTo(5.5f, 12f); lineTo(5.5f, 12.01f)
        moveTo(18.5f, 12f); lineTo(18.5f, 12.01f)
    }

    private val person = icon("person") {
        circle(12f, 8f, 3.5f)
        moveTo(5f, 20f); curveTo(5f, 14f, 19f, 14f, 19f, 20f)
    }

    private val fee = icon("fee") {
        moveTo(5f, 19f); lineTo(19f, 5f)
        circle(7f, 7f, 2.2f)
        circle(17f, 17f, 2.2f)
    }

    private val help = icon("help") {
        circle(12f, 12f, 9f)
        moveTo(9.5f, 9.5f); curveTo(9.5f, 6.5f, 14.5f, 6.5f, 14.5f, 9.5f); curveTo(14.5f, 11.5f, 12f, 11.5f, 12f, 14f)
        moveTo(12f, 17f); lineTo(12f, 17.01f)
    }

    private val salary = icon("salary") {
        box(3f, 6f, 21f, 20f)
        moveTo(3f, 10f); lineTo(21f, 10f)
        circle(17f, 15f, 1.2f)
        moveTo(7f, 6f); lineTo(7f, 4f); lineTo(17f, 4f); lineTo(17f, 6f)
    }

    private val refund = icon("refund") {
        moveTo(9f, 5f); lineTo(4f, 10f); lineTo(9f, 15f)
        moveTo(4f, 10f); lineTo(15f, 10f); curveTo(19f, 10f, 20f, 12f, 20f, 15f); lineTo(20f, 19f)
    }

    private val interest = icon("interest") {
        circle(12f, 12f, 9f)
        moveTo(8.5f, 15.5f); lineTo(15.5f, 8.5f)
        circle(9f, 9f, 1.2f)
        circle(15f, 15f, 1.2f)
    }

    private val cashback = icon("cashback") {
        circle(12f, 12f, 9f)
        moveTo(8f, 12f); lineTo(16f, 12f)
        moveTo(11f, 9f); lineTo(8f, 12f); lineTo(11f, 15f)
    }

    private val income = icon("income") {
        moveTo(12f, 3f); lineTo(12f, 14f)
        moveTo(7.5f, 10f); lineTo(12f, 14.5f); lineTo(16.5f, 10f)
        moveTo(4f, 16f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 16f)
    }

    private val swap = icon("swap") {
        moveTo(4f, 8f); lineTo(19f, 8f)
        moveTo(15f, 4f); lineTo(19f, 8f); lineTo(15f, 12f)
        moveTo(20f, 16f); lineTo(5f, 16f)
        moveTo(9f, 12f); lineTo(5f, 16f); lineTo(9f, 20f)
    }

    private val card = icon("card") {
        box(3f, 6f, 21f, 18f)
        moveTo(3f, 10f); lineTo(21f, 10f)
        moveTo(6f, 15f); lineTo(10f, 15f)
    }

    /** Shown for a key that is not in the set (a hand-edited `categories.yaml`, a damaged row). */
    val FALLBACK: ImageVector = help

    /** Every pickable icon, in picker order. */
    val all: List<IconOption> = listOf(
        IconOption("restaurant", R.string.icon_restaurant, restaurant),
        IconOption("grocery", R.string.icon_grocery, grocery),
        IconOption("shopping", R.string.icon_shopping, shopping),
        IconOption("transport", R.string.icon_transport, transport),
        IconOption("fuel", R.string.icon_fuel, fuel),
        IconOption("bill", R.string.icon_bill, bill),
        IconOption("phone", R.string.icon_phone, phone),
        IconOption("home", R.string.icon_home, home),
        IconOption("loan", R.string.icon_loan, loan),
        IconOption("investment", R.string.icon_investment, investment),
        IconOption("shield", R.string.icon_shield, shield),
        IconOption("health", R.string.icon_health, health),
        IconOption("entertainment", R.string.icon_entertainment, entertainment),
        IconOption("travel", R.string.icon_travel, travel),
        IconOption("education", R.string.icon_education, education),
        IconOption("personal", R.string.icon_personal, personal),
        IconOption("gift", R.string.icon_gift, gift),
        IconOption("cash", R.string.icon_cash, cash),
        IconOption("person", R.string.icon_person, person),
        IconOption("fee", R.string.icon_fee, fee),
        IconOption("help", R.string.icon_help, help),
        IconOption("salary", R.string.icon_salary, salary),
        IconOption("refund", R.string.icon_refund, refund),
        IconOption("interest", R.string.icon_interest, interest),
        IconOption("cashback", R.string.icon_cashback, cashback),
        IconOption("income", R.string.icon_income, income),
        IconOption("swap", R.string.icon_swap, swap),
        IconOption("card", R.string.icon_card, card),
    )

    private val byKey: Map<String, IconOption> = all.associateBy { it.key }

    fun vector(key: String): ImageVector = byKey[key]?.vector ?: FALLBACK

    @StringRes
    fun label(key: String): Int = byKey[key]?.label ?: R.string.icon_help
}
