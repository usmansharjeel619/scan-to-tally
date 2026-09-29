package com.acme.scantotally.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The relay API.
 *
 * Every call here can fail, and none of them failing is allowed to stop the
 * operator. The device validates and stores locally first; this is how the work
 * eventually reaches Tally, not how it gets done.
 */

@Serializable
data class SyncResponse(
    val syncedAt: String = "",
    /** The unit the relay creates new stock items with, e.g. "Nos". */
    val defaultUnit: String = "",
    val godown: String = "",
    val company: String = "",
    val items: List<ApiItem> = emptyList(),
    val bindings: List<ApiBinding> = emptyList(),
    val balances: List<ApiBalance> = emptyList(),
    val receivedBoxes: List<ApiReceivedBox> = emptyList(),
    val proposals: List<ApiProposal> = emptyList(),
    val catalogue: List<ApiCatalogue> = emptyList(),
    val orders: List<ApiOrder> = emptyList(),
    val historyRemovals: List<ApiHistoryRemoval> = emptyList(),
)

@Serializable
data class ApiHistoryRemoval(
    val sessionId: String,
    val stockItemName: String,
    val boxSerial: String,
)

/** What became of a product the operator described. */
@Serializable
data class ApiProposal(
    val pid: String = "",
    val name: String = "",
    val description: String = "",
    val baseUnits: String = "",
    val state: String = "",
    val error: String = "",
)

@Serializable
data class ApiItem(
    val name: String,
    val alias: String = "",
    @SerialName("part_no") val partNo: String = "",
    @SerialName("base_units") val baseUnits: String = "",
    @SerialName("has_batches") val hasBatches: Int = 1,
)

@Serializable
data class ApiBinding(
    val pid: String,
    @SerialName("stock_item_name") val stockItemName: String,
    val description: String = "",
)

@Serializable
data class ApiBalance(
    @SerialName("stock_item_name") val stockItemName: String,
    @SerialName("batch_name") val batchName: String,
    @SerialName("godown_name") val godownName: String,
    @SerialName("closing_qty") val closingQty: Double,
    val unit: String = "",
    @SerialName("synced_at") val syncedAt: String = "",
)

@Serializable
data class ApiReceivedBox(
    val pid: String,
    @SerialName("box_serial") val boxSerial: String,
    @SerialName("received_at") val receivedAt: String = "",
)

@Serializable
data class ApiCatalogue(
    val pid: String,
    val description: String = "",
    /** JSON array as stored; parsed on the device only when shown. */
    val alternates: String = "",
)

@Serializable
data class ApiOrderLine(
    @SerialName("voucher_number") val voucherNumber: String = "",
    @SerialName("stock_item_name") val stockItemName: String,
    @SerialName("ordered_qty") val orderedQty: Double = 0.0,
    @SerialName("delivered_qty") val deliveredQty: Double = 0.0,
    val unit: String = "",
)

@Serializable
data class ApiOrder(
    @SerialName("voucher_number") val voucherNumber: String,
    @SerialName("party_name") val partyName: String = "",
    @SerialName("order_date") val orderDate: String = "",
    val lines: List<ApiOrderLine> = emptyList(),
)

@Serializable
data class ConnectorStatus(
    val health: String = "UNKNOWN",
    val lastError: String = "",
    val company: String = "",
    val queuedJobs: Int = 0,
    val failedJobs: Int = 0,
)

@Serializable
data class StatusResponse(
    val connector: ConnectorStatus = ConnectorStatus(),
    val pending: Int = 0,
    val failed: Int = 0,
)

@Serializable
data class CreateSessionRequest(
    val sessionId: String,
    val kind: String,
    val party: String = "",
    val salesOrder: String = "",
    val godown: String = "",
    val narration: String = "",
    /**
     * The company this count was raised against.
     *
     * Sent so the relay can refuse a count that belongs somewhere else. It
     * matters most for work scanned with no signal, where the phone had no way
     * to notice the change itself and would otherwise drain its outbox into
     * whichever company the relay had moved on to.
     */
    val company: String = "",
)

@Serializable
data class LineRequest(
    val pid: String,
    val boxSerial: String,
    val qty: Double,
    val lineId: Long? = null,
    val raw: String = "",
    val symbology: String = "",
    val mfgDate: String? = null,
    val manual: Boolean = false,
)

@Serializable
data class SubmitRequest(val scope: String = "PARTIAL")

@Serializable
data class SubmitResponse(
    val sessionId: String = "",
    val ok: Boolean = false,
    val state: String = "",
    val dispatched: Boolean = false,
    val unresolvedLines: Int = 0,
    val noVariance: Boolean = false,
    val message: String = "",
)

/** The shape the relay refuses things in. */
@Serializable
data class ApiError(
    val error: String = "",
    val message: String = "",
)

@Serializable
data class SessionStateResponse(val session: ApiSession = ApiSession())

/** Answer, absence, or neither. */
data class SessionLookup(
    val session: ApiSession? = null,
    val found: Boolean = false,
    val gone: Boolean = false,
)

@Serializable
data class ApiSession(
    val id: String = "",
    val state: String = "",
    @SerialName("tally_voucher_id") val tallyVoucherId: String = "",
    @SerialName("error_class") val errorClass: String = "",
    @SerialName("error_message") val errorMessage: String = "",
)

@Serializable
data class VarianceRow(
    val stockItemName: String = "",
    val pid: String = "",
    val boxSerial: String = "",
    val unit: String = "",
    val countedQty: Double = 0.0,
    val bookQty: Double = 0.0,
    val variance: Double = 0.0,
    val kind: String = "",
)

@Serializable
data class VarianceReport(
    val rows: List<VarianceRow> = emptyList(),
    val scope: String = "PARTIAL",
    val counted: Int = 0,
    val matched: Int = 0,
    val discrepancies: Int = 0,
    val notCounted: Int = 0,
    val willZeroUncounted: Boolean = false,
)

@Serializable
data class ProposeItemRequest(
    val pid: String,
    val description: String,
    val baseUnits: String = "NO",
    val batchwise: Boolean = true,
    val trackMfgDate: Boolean = true,
    val sessionId: String = "",
    val raw: String = "",
    val proposedBy: String = "",
)

@Serializable
data class ProposeItemResponse(
    val ok: Boolean = false,
    val pid: String = "",
    val name: String = "",
    val state: String = "",
    val error: String? = null,
    val stockItemName: String? = null,
    val message: String? = null,
)

class RelayApi(
    private val baseUrl: String,
    private val token: String,
) {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
        }
        install(HttpTimeout) {
            // Short, on purpose. A slow relay must not make the operator wait:
            // the work is already durable on the device.
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 8_000
        }
        defaultRequest {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun status(): StatusResponse = client.get("$baseUrl/api/v1/status").body()

    suspend fun sync(): SyncResponse = client.get("$baseUrl/api/v1/sync").body()

    /**
     * Opens a session on the relay, reporting whether it was actually accepted.
     *
     * The status used to be discarded. A refusal -- a count raised against
     * another company, most importantly -- looked exactly like success, so the
     * lines went up afterwards regardless and attached themselves to nothing.
     */
    suspend fun createSession(req: CreateSessionRequest): Boolean =
        client.post("$baseUrl/api/v1/sessions") { setBody(req) }.status.isSuccess()

    suspend fun addLine(sessionId: String, req: LineRequest): Boolean {
        val resp = client.post("$baseUrl/api/v1/sessions/$sessionId/lines") { setBody(req) }
        return resp.status.isSuccess()
    }

    /**
     * Closes a receipt.
     *
     * A refusal comes back as a normal answer with `ok = false` rather than an
     * exception: the reason is the whole point, and it was being thrown away.
     * A receipt that will not save and does not say why is the worst thing this
     * app can put in front of an operator.
     */
    suspend fun submit(sessionId: String, scope: String = "PARTIAL"): SubmitResponse {
        val resp = client.post("$baseUrl/api/v1/sessions/$sessionId/submit") {
            setBody(SubmitRequest(scope))
        }
        if (resp.status.isSuccess()) return resp.body<SubmitResponse>().copy(ok = true)
        val err = runCatching { resp.body<ApiError>() }.getOrNull()
        return SubmitResponse(
            sessionId = sessionId,
            ok = false,
            message = err?.message?.ifEmpty { null }
                ?: err?.error?.ifEmpty { null }
                ?: "Tally would not accept this (${resp.status.value}).",
        )
    }

    suspend fun sessionState(sessionId: String): SessionStateResponse =
        client.get("$baseUrl/api/v1/sessions/$sessionId").body()

    /**
     * The relay's view of a receipt, with "no such receipt" told apart from
     * "could not ask".
     *
     * Those two look identical through a thrown exception, and they mean
     * opposite things: one says the receipt is gone, the other says the phone
     * has no signal. Acting on the wrong one would delete work.
     */
    suspend fun sessionStateOrGone(sessionId: String): SessionLookup {
        val resp = client.get("$baseUrl/api/v1/sessions/$sessionId")
        if (resp.status == HttpStatusCode.NotFound) return SessionLookup(gone = true)
        if (!resp.status.isSuccess()) return SessionLookup()
        return SessionLookup(session = resp.body<SessionStateResponse>().session, found = true)
    }

    suspend fun variance(sessionId: String, scope: String): VarianceReport =
        client.get("$baseUrl/api/v1/sessions/$sessionId/variance?scope=$scope").body()



    suspend fun deleteSession(sessionId: String) {
        // No content type. The client sets application/json by default, and a
        // DELETE carries no body, which the relay rejects as a malformed one --
        // every Discard and Clear was quietly answered 400 because of it.
        client.delete("$baseUrl/api/v1/sessions/$sessionId") {
            headers.remove(HttpHeaders.ContentType)
        }
    }

    suspend fun retry(sessionId: String): Boolean {
        val resp = client.post("$baseUrl/api/v1/sessions/$sessionId/retry") {
            setBody(SubmitRequest())
        }
        return resp.status.isSuccess()
    }

    /**
     * Describes a product Tally has never seen.
     *
     * Sent the moment it is scanned, while the operator still has the carton in
     * hand and the description printed on the label -- by the end of a session
     * the box is on a shelf and they are recalling, not reading.
     */
    suspend fun proposeItem(req: ProposeItemRequest): ProposeItemResponse {
        val resp = client.post("$baseUrl/api/v1/proposed-items") { setBody(req) }
        if (resp.status.isSuccess()) return resp.body<ProposeItemResponse>().copy(ok = true)
        // Same trap as submit: a refusal deserialised into the success shape
        // reads as a success with empty fields, and the operator walks away
        // believing a product was created that never was.
        val err = runCatching { resp.body<ApiError>() }.getOrNull()
        return ProposeItemResponse(
            ok = false, pid = req.pid,
            error = err?.error?.ifEmpty { null } ?: "http_${resp.status.value}",
            message = err?.message?.ifEmpty { null }
                ?: "Could not add this product (${resp.status.value}).",
        )
    }
}
