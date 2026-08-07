package moundcity.transit.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ReferenceViewModel : LightViewModel<Unit>()

/**
 * Doc 02 §3.6: five cards, static, versioned, dated. Contacts are read-only
 * by design (M6: no LightServiceMethod dials) — typeset for reading aloud.
 * The fares card = pipeline rows + curated copy (decision of 2026-08-05).
 */
class ReferenceScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ReferenceViewModel>(sealedActivity) {

    override val viewModelClass: Class<ReferenceViewModel> get() = ReferenceViewModel::class.java
    override fun createViewModel(): ReferenceViewModel = ReferenceViewModel()

    /** Every throwing access lives here, once — a malformed bundled asset
     *  degrades to a message, never a crash loop (review finding 11). */
    private class Cards(
        val faresAsOf: String,
        val fareRows: List<Pair<String, String?>>,
        val contactsCapturedOn: String,
        val contactRows: List<Pair<String, String?>>,
    )

    private fun parseCards(): Cards? = runCatching {
        val json = Json { ignoreUnknownKeys = true }
        val fares = json.parseToJsonElement(AppGraph.referenceJson.getValue("fares")).jsonObject
        val contacts = json.parseToJsonElement(AppGraph.referenceJson.getValue("contacts")).jsonObject
        Cards(
            faresAsOf = fares.getValue("as_of").jsonPrimitive.content,
            fareRows = fares.getValue("fares").jsonArray.map { el ->
                val f = el.jsonObject
                "${f.getValue("label").jsonPrimitive.content}  ${f.getValue("price").jsonPrimitive.content}" to
                    f.getValue("notes").jsonPrimitive.content.ifEmpty { null }
            },
            contactsCapturedOn = contacts.getValue("capturedOn").jsonPrimitive.content,
            contactRows = contacts.getValue("contacts").jsonArray.map { el ->
                val c = el.jsonObject
                "${c.getValue("label").jsonPrimitive.content}  ${c.getValue("phone").jsonPrimitive.content}" to
                    c.getValue("hours").jsonPrimitive.content.ifEmpty { null }
            },
        )
    }.getOrNull()

    @Composable
    override fun Content() {
        val cards = remember { parseCards() }
        MctPage(title = "Reference", onBack = { goBack() }) {
            if (cards == null) {
                MctRow(primary = "Reference data unavailable.", secondary = "The bundled reference files could not be read. Reinstalling restores them.")
                return@MctPage
            }
            LazyColumn {
                item { MctRow(primary = "— fares · as of ${cards.faresAsOf} — verify before you rely on them —") }
                items(cards.fareRows) { (label, notes) ->
                    MctRow(primary = label, secondary = notes)
                }
                item {
                    MctRow(
                        primary = "Children 4 and under free; ages 5–12 half price. Seniors 65+ half price with a permit.",
                        secondary = "Illinois (St. Clair County) prices match, plus a \$3.00 2-hour pass Metro's own page does not list. The \$1.00 bus fare is temporary per Metro (paper-transfer suspension); gate rollout completes 2026-08-17. Copy reviewed 2026-08-05.",
                    )
                }
                item { MctRow(primary = "— payment & passes —") }
                item {
                    MctRow(
                        primary = "Ticket vending machines at all rail stations and transit centers; Transit and Ride On apps; reloadable Ride On card; exact cash in the farebox (no change); Tap & Ride with automatic fare capping.",
                        secondary = "Copy reviewed 2026-08-05.",
                    )
                }
                item { MctRow(primary = "— accessibility —") }
                item {
                    MctRow(
                        primary = "Every bus has a lift or ramp and a two-bike rack (two bikes, first come). All rail stations are ADA accessible. Per-stop wheelchair access appears on each stop's departures screen, straight from the feed.",
                        secondary = "2,978 of 5,118 stops are flagged not accessible in the current schedule.",
                    )
                }
                item { MctRow(primary = "— contacts · captured ${cards.contactsCapturedOn} · read-only —") }
                items(cards.contactRows) { (label, hours) ->
                    MctRow(primary = label, secondary = hours)
                }
                item { MctRow(primary = "— services —") }
                item {
                    MctRow(
                        primary = "Call-A-Ride: ADA paratransit. Book ahead; 30-minute pickup window; two-hour cancellation rule.",
                        secondary = "Via: shared rides, \$2, seven-mile cap, three zones. Hours vary — call.",
                    )
                }
            }
        }
    }
}
