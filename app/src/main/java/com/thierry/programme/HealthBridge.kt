package com.thierry.programme

import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/**
 * Pont Health Connect → JavaScript.
 * Exposé dans la WebView sous le nom « HealthBridge ».
 * Le résultat est renvoyé à la page via la fonction JS onHealthData(json).
 * Doit être créé dans MainActivity.onCreate (enregistrement du lanceur d'autorisations).
 */
class HealthBridge(private val activity: ComponentActivity, private val webView: WebView) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var askedThisSession = false

    private val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    private val permissionLauncher: ActivityResultLauncher<Set<String>> =
        activity.registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { _ -> fetch() }

    // ───────── Méthodes appelées depuis le JavaScript ─────────

    /** Demande les autorisations si besoin, puis lit les données. */
    @JavascriptInterface
    fun requestData() {
        activity.runOnUiThread {
            val status = HealthConnectClient.getSdkStatus(activity)
            if (status != HealthConnectClient.SDK_AVAILABLE) {
                send(JSONObject().put("status",
                    if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) "update_required"
                    else "unavailable"))
                return@runOnUiThread
            }
            scope.launch {
                try {
                    val client = HealthConnectClient.getOrCreate(activity)
                    val granted = client.permissionController.getGrantedPermissions()
                    if (!granted.containsAll(permissions) && !askedThisSession) {
                        askedThisSession = true
                        permissionLauncher.launch(permissions)
                    } else {
                        fetch()
                    }
                } catch (e: Exception) {
                    send(JSONObject().put("status", "error").put("message", e.toString()))
                }
            }
        }
    }

    /** Force une nouvelle demande d'autorisation (bouton « Autoriser »). */
    @JavascriptInterface
    fun askPermissions() {
        askedThisSession = false
        requestData()
    }

    /** Ouvre les réglages Health Connect (gestion des autorisations / sources). */
    @JavascriptInterface
    fun openSettings() {
        activity.runOnUiThread {
            val action = if (android.os.Build.VERSION.SDK_INT >= 34)
                "android.health.connect.action.HEALTH_HOME_SETTINGS"
            else "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
            try { activity.startActivity(Intent(action)) } catch (_: Exception) { }
        }
    }

    fun destroy() = scope.cancel()

    // ───────── Lecture des données ─────────

    private fun fetch() {
        scope.launch {
            val json = try {
                withContext(Dispatchers.IO) { readAll() }
            } catch (e: Exception) {
                JSONObject().put("status", "error").put("message", e.toString())
            }
            send(json)
        }
    }

    private suspend fun readAll(): JSONObject {
        val client = HealthConnectClient.getOrCreate(activity)
        val granted = client.permissionController.getGrantedPermissions()
        val out = JSONObject()
        out.put("status", if (granted.isEmpty()) "denied" else "ok")
        out.put("granted", JSONArray(granted.map { it.substringAfterLast('.') }))
        out.put("updated", Instant.now().toString())
        if (granted.isEmpty()) return out

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val weekStart = today.minusDays(6).atStartOfDay()
        val nowLdt = LocalDateTime.now(zone)
        val now = Instant.now()

        // Calories brûlées totales (métabolisme + activité) + pas : total par jour sur 7 jours (aujourd'hui inclus)
        val days = JSONArray()
        val byDay = linkedMapOf<LocalDate, JSONObject>()
        for (i in 6 downTo 0) {
            val d = today.minusDays(i.toLong())
            val o = JSONObject().put("date", d.toString())
            byDay[d] = o
            days.put(o)
        }
        if (has(granted, "READ_TOTAL_CALORIES_BURNED")) safe {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(weekStart, nowLdt),
                    timeRangeSlicer = Period.ofDays(1)
                )
            ).forEach { g ->
                val kcal = g.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                if (kcal != null) byDay[g.startTime.toLocalDate()]?.put("kcal", Math.round(kcal))
            }
        }
        if (has(granted, "READ_STEPS")) safe {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(weekStart, nowLdt),
                    timeRangeSlicer = Period.ofDays(1)
                )
            ).forEach { g ->
                val steps = g.result[StepsRecord.COUNT_TOTAL]
                if (steps != null) byDay[g.startTime.toLocalDate()]?.put("steps", steps)
            }
        }
        out.put("days", days)

        val last30 = TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now)

        // Poids : toutes les mesures sur 30 jours (pour la tendance)
        if (has(granted, "READ_WEIGHT")) safe {
            val recs = client.readRecords(
                ReadRecordsRequest(WeightRecord::class, last30, ascendingOrder = true, pageSize = 500)
            ).records
            val arr = JSONArray()
            recs.forEach {
                arr.put(JSONObject().put("t", it.time.toString()).put("kg", round1(it.weight.inKilograms)))
            }
            out.put("weights", arr)
        }
        // Masse grasse (%)
        if (has(granted, "READ_BODY_FAT")) safe {
            client.readRecords(
                ReadRecordsRequest(BodyFatRecord::class, last30, ascendingOrder = false, pageSize = 1)
            ).records.firstOrNull()?.let {
                out.put("bodyFat", JSONObject().put("t", it.time.toString()).put("pct", round1(it.percentage.value)))
            }
        }
        // Masse maigre (kg)
        if (has(granted, "READ_LEAN_BODY_MASS")) safe {
            client.readRecords(
                ReadRecordsRequest(LeanBodyMassRecord::class, last30, ascendingOrder = false, pageSize = 1)
            ).records.firstOrNull()?.let {
                out.put("leanMass", JSONObject().put("t", it.time.toString()).put("kg", round1(it.mass.inKilograms)))
            }
        }
        // Métabolisme de base (kcal/jour)
        if (has(granted, "READ_BASAL_METABOLIC_RATE")) safe {
            client.readRecords(
                ReadRecordsRequest(BasalMetabolicRateRecord::class, last30, ascendingOrder = false, pageSize = 1)
            ).records.firstOrNull()?.let {
                out.put("bmr", JSONObject().put("t", it.time.toString())
                    .put("kcal", Math.round(it.basalMetabolicRate.inKilocaloriesPerDay)))
            }
        }
        // Sommeil : sessions des 8 dernières nuits
        if (has(granted, "READ_SLEEP")) safe {
            val recs = client.readRecords(
                ReadRecordsRequest(
                    SleepSessionRecord::class,
                    TimeRangeFilter.between(now.minus(Duration.ofDays(8)), now),
                    ascendingOrder = true, pageSize = 100
                )
            ).records
            val arr = JSONArray()
            recs.forEach {
                arr.put(JSONObject()
                    .put("start", it.startTime.toString())
                    .put("end", it.endTime.toString())
                    .put("min", Duration.between(it.startTime, it.endTime).toMinutes()))
            }
            out.put("sleep", arr)
        }
        return out
    }

    /** Les autorisations ont la forme android.permission.health.READ_XXX */
    private fun has(granted: Set<String>, perm: String): Boolean = granted.any { it.endsWith(".$perm") }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Exception) { }
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private fun send(json: JSONObject) {
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "window.onHealthData && window.onHealthData($json);", null
            )
        }
    }
}
