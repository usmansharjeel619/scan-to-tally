package com.acme.scantotally.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
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
    val godown: String = "",
    val company: String = "",
    val items: List<ApiItem> = emptyList(),
    val bindings: List<ApiBinding> = emptyList(),
    val balances: List<ApiBalance> = emptyList(),
    val receivedBoxes: List<ApiReceivedBox> = emptyList(),
    val catalogue: List<ApiCatalogue> = emptyList(),
    val orders: List<ApiOrder> = emptyList(),
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
    val state: String = "",
    val dispatched: Boolean = false,
    val unresolvedLines: Int = 0,
    val noVariance: Boolean = false,
    val message: String = "",
)

@Serializable
data class SessionStateResponse(val session: ApiSession = ApiSession())

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

    suspend fun createSession(req: CreateSessionRequest) {
        client.post("$baseUrl/api/v1/sessions") { setBody(req) }
    }

    suspend fun addLine(sessionId: String, req: LineRequest): Boolean {
        val resp = client.post("$baseUrl/api/v1/sessions/$sessionId/lines") { setBody(req) }
        return resp.status.isSuccess()
    }

    suspend fun submit(sessionId: String, scope: String = "PARTIAL"): SubmitResponse =
        client.post("$baseUrl/api/v1/sessions/$sessionId/submit") {
            setBody(SubmitRequest(scope))
        }.body()

    suspend fun sessionState(sessionId: String): SessionStateResponse =
        client.get("$baseUrl/api/v1/sessions/$sessionId").body()

    suspend fun variance(sessionId: String, scope: String): VarianceReport =
        client.get("$baseUrl/api/v1/sessions/$sessionId/variance?scope=$scope").body()



    suspend fun retry(sessionId: String) {
        client.post("$baseUrl/api/v1/sessions/$sessionId/retry") { setBody(SubmitRequest()) }
    }

    /**
     * Describes a product Tally has never seen.
     *
     * Sent the moment it is scanned, while the operator still has the carton in
     * hand and the description printed on the label -- by the end of a session
     * the box is on a shelf and they are recalling, not reading.
     */
    suspend fun proposeItem(req: ProposeItemRequest): ProposeItemResponse =
        client.post("$baseUrl/api/v1/proposed-items") { setBody(req) }.body()
}
