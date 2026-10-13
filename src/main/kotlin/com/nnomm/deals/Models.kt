package com.nnomm.deals

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.sql.ResultSet

@Serializable
data class DealResponse(
    val id: String,
    @SerialName("restaurant_id") val restaurantId: String,
    val title: String,
    @SerialName("price_paise") val pricePaise: Long,
    @SerialName("stock_total") val stockTotal: Int,
    @SerialName("stock_available") val stockAvailable: Int,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
)

@Serializable
data class ClaimResponse(
    val id: String,
    @SerialName("deal_id") val dealId: String,
    @SerialName("customer_id") val customerId: String,
    val quantity: Int,
    @SerialName("amount_paise") val amountPaise: Long,
    val status: String,
    @SerialName("expires_at") val expiresAt: String,
)

const val DEAL_COLUMNS = "id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at"

fun ResultSet.toDeal() = DealResponse(
    id = uuid("id").toString(),
    restaurantId = uuid("restaurant_id").toString(),
    title = getString("title"),
    pricePaise = getLong("price_paise"),
    stockTotal = getInt("stock_total"),
    stockAvailable = getInt("stock_available"),
    startsAt = instant("starts_at").toString(),
    endsAt = instant("ends_at").toString(),
)

/**
 * Selects a claim and whether it is RESERVED but already past expires_at by the database clock.
 * Such a claim is reported as EXPIRED even if the sweeper hasn't reached it yet (rule 4).
 */
const val CLAIM_COLUMNS = """
    c.id, c.deal_id, c.customer_id, c.quantity, c.amount_paise, c.expires_at,
    CASE WHEN c.status = 'RESERVED' AND c.expires_at <= clock_timestamp() THEN 'EXPIRED' ELSE c.status END AS shown_status
"""

fun ResultSet.toClaim() = ClaimResponse(
    id = uuid("id").toString(),
    dealId = uuid("deal_id").toString(),
    customerId = uuid("customer_id").toString(),
    quantity = getInt("quantity"),
    amountPaise = getLong("amount_paise"),
    status = getString("shown_status"),
    expiresAt = instant("expires_at").toString(),
)
