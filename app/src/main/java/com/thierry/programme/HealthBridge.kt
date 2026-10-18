package com.thierry.programme

import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
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
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/**
 * v8.4 — Pont Health Connect → JavaScript (réécriture).
 * Exposé dans la WebView sous le nom « HealthBridge » ; le résultat est renvoyé via window.onHealthData(json).
 *
 * Modèle calories v8.3 (calculé ici, la page ne fait qu'afficher) — par jour, sur les 30 derniers jours de la fenêtre :
 *   total = TotalCaloriesBurned Google Health / Google Fit (= ce qu'affiche l'appli Fitbit : BMR + activité)
 *         + sessions OnePlus Watch 2R : ActiveCaloriesBurned OnePlus pendant chaque session
 *   Le BMR de la balance Xiaomi S400 n'entre plus dans le calcul (affichage informatif uniquement).
 *   Jour sans donnée Google Health : pas de modèle (la page se replie sur l'historique / Health Connect brut).
 */
class HealthBridge(private val activity: ComponentActivity, private val webView: WebView) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var askedThisSession = false
    /** Fenêtre demandée par la page : "week" (lun → dim), "month" (30 j), "year" (année civile) */
    @Volatile private var period = "week"

    private val corePermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class)
    )
    /** Température cutanée : demandée seulement si Health Connect la prend en charge sur ce téléphone */
    private val skinTempPermission = HealthPermission.getReadPermission(SkinTemperatureRecord::class)

    private fun resolvePermissions(client: HealthConnectClient): Set<String> {
        val skinOk = try {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (_: Throwable) { false }
        return if (skinOk) corePermissions + skinTempPermission else corePermissions
    }

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
                    val permissions = resolvePermissions(client)
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

    /** Comme requestData(), avec la période d'historique : "week", "month" ou "year". */
    @JavascriptInterface
    fun requestDataForPeriod(p: String?) {
        period = when (p) { "month", "year" -> p; else -> "week" }
        requestData()
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

    // ───────── Lecture ─────────

    private fun fetch() {
        val p = period // figée pour cette lecture (plusieurs lectures peuvent se chevaucher)
        scope.launch {
            val json = try {
                withContext(Dispatchers.IO) { readAll(p) }
            } catch (e: Exception) {
                JSONObject().put("status", "error").put("message", e.toString())
            }
            send(json)
        }
    }

    private suspend fun readAll(p: String): JSONObject {
        val client = HealthConnectClient.getOrCreate(activity)
        val permissions = resolvePermissions(client)
        val granted = client.permissionController.getGrantedPermissions()
        val out = JSONObject()
        out.put("status", if (granted.isEmpty()) "denied" else "ok")
        out.put("requested", permissions.size)
        out.put("grantedCount", granted.count { it in permissions })
        out.put("updated", Instant.now().toString())
        if (granted.isEmpty()) return out

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val nowLdt = LocalDateTime.now(zone)
        val now = Instant.now()
        // Fenêtres : semaine fixe (lundi → dimanche), 30 derniers jours, année civile
        val firstDay: LocalDate
        val lastDay: LocalDate
        when (p) {
            "year" -> { firstDay = today.withDayOfYear(1); lastDay = today.withDayOfYear(today.lengthOfYear()) }
            "month" -> { firstDay = today.minusDays(29); lastDay = today }
            else -> { firstDay = today.with(DayOfWeek.MONDAY); lastDay = firstDay.plusDays(6) }
        }
        out.put("period", p)
        out.put("from", firstDay.toString())
        out.put("to", lastDay.toString())

        // Série quotidienne sur toute la fenêtre (jours futurs vides)
        val days = JSONArray()
        val byDay = linkedMapOf<LocalDate, JSONObject>()
        var d = firstDay
        while (!d.isAfter(lastDay)) {
            val o = JSONObject().put("date", d.toString())
            byDay[d] = o
            days.put(o)
            d = d.plusDays(1)
        }
        // Total brut Health Connect (info / jours hors modèle) — jamais utilisé pour les 30 derniers jours
        if (has(granted, "READ_TOTAL_CALORIES_BURNED")) safe {
            daily(client, byDay, firstDay, today, nowLdt, TotalCaloriesBurnedRecord.ENERGY_TOTAL) { o, v ->
                o.put("kcalHC", Math.round(v.inKilocalories))
            }
        }
        if (has(granted, "READ_STEPS")) safe {
            daily(client, byDay, firstDay, today, nowLdt, StepsRecord.COUNT_TOTAL) { o, v -> o.put("steps", v) }
        }
        if (has(granted, "READ_FLOORS_CLIMBED")) safe {
            daily(client, byDay, firstDay, today, nowLdt, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL) { o, v -> o.put("floors", Math.round(v)) }
        }
        if (has(granted, "READ_RESTING_HEART_RATE")) safe {
            var token: String? = null
            do {
                val resp = client.readRecords(
                    ReadRecordsRequest(
                        RestingHeartRateRecord::class,
                        TimeRangeFilter.between(firstDay.atStartOfDay(zone).toInstant(), now),
                        ascendingOrder = true, pageSize = 1000, pageToken = token
                    )
                )
                resp.records.forEach { byDay[it.time.atZone(zone).toLocalDate()]?.put("rhr", it.beatsPerMinute) }
                token = resp.pageToken
            } while (token != null)
        }

        // Modèle calories : les 30 derniers jours de la fenêtre (jusqu'à aujourd'hui)
        val bmr24 = getBMRFromPref()
        out.put("bmr24", Math.round(bmr24.first))
        out.put("bmrSource", if (bmr24.second) "xiaomi" else "defaut")
        val modelEnd = if (lastDay.isBefore(today)) lastDay else today
        val modelStart = if (firstDay.isAfter(today.minusDays(29))) firstDay else today.minusDays(29)
        if (!modelEnd.isBefore(modelStart)) safe {
            calories(client, granted, byDay, modelStart, modelEnd, today, zone, now)?.let { src ->
                out.put("caloriesSources", JSONObject()
                    .put("googleHealth", src.getLong("googleHealth"))
                    .put("oneplusExercise", src.getLong("oneplusExercise"))
                    .put("totalActives", src.getLong("totalActives")))
                out.put("exerciseSessions", src.getJSONArray("sessions"))
            }
        }
        out.put("days", days)

        readBody(client, granted, out, now)
        readSleep(client, granted, out, zone, now)
        readHeart(client, granted, out, today, zone, now)
        return out
    }

    // ───────── Modèle calories ─────────

    private suspend fun calories(
        client: HealthConnectClient, granted: Set<String>, byDay: Map<LocalDate, JSONObject>,
        from: LocalDate, to: LocalDate, today: LocalDate, zone: ZoneId, now: Instant
    ): JSONObject? {
        val hasTotal = has(granted, "READ_TOTAL_CALORIES_BURNED")
        val hasOnePlus = has(granted, "READ_EXERCISE") && has(granted, "READ_ACTIVE_CALORIES_BURNED")
        if (!hasTotal && !hasOnePlus) return null
        val winStart = from.atStartOfDay(zone).toInstant()
        val winEnd = if (to == today) now else to.plusDays(1).atStartOfDay(zone).toInstant()

        // Sessions OnePlus de la fenêtre (rattachées au jour de leur début)
        val sessions = ArrayList<ExerciseSessionRecord>()
        if (hasOnePlus) safe {
            var token: String? = null
            do {
                val resp = client.readRecords(
                    ReadRecordsRequest(
                        ExerciseSessionRecord::class, TimeRangeFilter.between(winStart, winEnd),
                        ascendingOrder = true, pageSize = 500, pageToken = token
                    )
                )
                resp.records.forEach {
                    if (!it.startTime.isBefore(winStart) && isOnePlus(it.metadata.dataOrigin.packageName)) sessions.add(it)
                }
                token = resp.pageToken
            } while (token != null)
        }

        var todaySrc: JSONObject? = null
        var d = from
        while (!d.isAfter(to)) {
            val o = byDay[d]
            if (o == null) { d = d.plusDays(1); continue }
            val dStart = d.atStartOfDay(zone).toInstant()
            val dEnd = if (d == today) now else d.plusDays(1).atStartOfDay(zone).toInstant()

            // 1. Fitbit Air (Google Health / Google Fit / package Health Connect du Fitbit) : TotalCaloriesBurned
            //    = ce qu'affiche l'appli Fitbit (BMR + activité). Plusieurs relais du même Fitbit → le plus élevé.
            val bySrc = if (hasTotal) totalBySource(client, dStart, dEnd) else emptyMap()
            // v8.4 — base = agrégat Health Connect ENERGY_TOTAL (fusion des sources par priorité + BMR des trous),
            //        exactement le chiffre de l'écran « Énergie dépensée » de Health Connect / appli Fitbit.
            //        Repli : la source Fitbit la plus élevée si l'agrégat échoue.
            val hcTotal = if (hasTotal) try {
                client.aggregate(AggregateRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    TimeRangeFilter.between(dStart, dEnd)))[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
            } catch (_: Exception) { 0.0 } else 0.0
            val fbMax = bySrc.filterKeys { isFitbit(it) && !isOnePlus(it) }.values.maxOrNull() ?: 0.0
            val googleHealth = if (hcTotal > 0) hcTotal else fbMax
            val googleSrc = if (hcTotal > 0) "healthconnect" else
                (bySrc.filterKeys { isFitbit(it) && !isOnePlus(it) }.maxByOrNull { it.value }?.key ?: "")
            // v8.4 — diagnostic : total TotalCaloriesBurned de chaque package (aujourd'hui uniquement)
            val diag = JSONArray()
            if (d == today) bySrc.entries.sortedByDescending { it.value }.forEach {
                diag.put(JSONObject().put("pkg", it.key).put("kcal", Math.round(it.value)))
            }

            // 2. Sessions OnePlus : calories actives OnePlus pendant chaque session
            var calsOnePlus = 0.0
            val list = JSONArray()
            sessions.filter { !it.startTime.isBefore(dStart) && it.startTime.isBefore(dEnd) }
                .sortedBy { it.startTime }
                .forEach { s ->
                    val pkg = s.metadata.dataOrigin.packageName
                    val sEnd = minOf(s.endTime, dEnd)
                    if (!sEnd.isAfter(s.startTime)) return@forEach
                    val sKcal = activeKcal(client, s.startTime, sEnd, setOf(DataOrigin(pkg)))
                    calsOnePlus += sKcal
                    list.put(JSONObject()
                        .put("label", getExerciseLabel(s.exerciseType))
                        .put("type", getExerciseLabel(s.exerciseType))
                        .put("title", s.title ?: "")
                        .put("source", "oneplus")
                        .put("src", "oneplus")
                        .put("start", s.startTime.toString())
                        .put("end", sEnd.toString())
                        .put("min", Math.round(Duration.between(s.startTime, sEnd).seconds / 60.0))
                        .put("kcal", Math.round(sKcal)))
                }

            // 3. Total = Google Health + sessions OnePlus (pas de jour sans donnée Google : repli sur l'historique)
            if (googleHealth <= 0 && d == today && diag.length() > 0) o.put("calDiag", diag)
            if (googleHealth > 0) {
                val totalBrulees = googleHealth + calsOnePlus
                o.put("cal", JSONObject()
                    .put("googleHealth", Math.round(googleHealth))      // Fitbit Air : BMR + activité
                    .put("oneplusExercise", Math.round(calsOnePlus))    // sessions OnePlus
                    .put("totalActives", Math.round(totalBrulees))
                    .put("actives", Math.round(totalBrulees))
                    .put("total", Math.round(totalBrulees))
                    .put("sessions", list)
                    .put("googleSrc", googleSrc)
                    .put("diag", diag))
                if (d == today) todaySrc = JSONObject()
                    .put("googleHealth", Math.round(googleHealth))
                    .put("oneplusExercise", Math.round(calsOnePlus))
                    .put("totalActives", Math.round(totalBrulees))
                    .put("sessions", list)
            }
            d = d.plusDays(1)
        }
        return todaySrc
    }

    /**
     * v8.4 — TotalCaloriesBurned par package sur une plage (relevés au prorata de leur part dans la plage).
     */
    private suspend fun totalBySource(client: HealthConnectClient, a: Instant, b: Instant): Map<String, Double> {
        val bySrc = HashMap<String, Double>()
        if (!b.isAfter(a)) return bySrc
        try {
            var token: String? = null
            do {
                val resp = client.readRecords(
                    ReadRecordsRequest(
                        TotalCaloriesBurnedRecord::class, TimeRangeFilter.between(a, b),
                        ascendingOrder = true, pageSize = 1000, pageToken = token
                    )
                )
                resp.records.forEach { r ->
                    val p = r.metadata.dataOrigin.packageName
                    val len = Duration.between(r.startTime, r.endTime).seconds
                    val ov = overlapSec(r.startTime, r.endTime, a, b)
                    val k = if (len <= 0) r.energy.inKilocalories else r.energy.inKilocalories * ov / len
                    bySrc[p] = (bySrc[p] ?: 0.0) + k
                }
                token = resp.pageToken
            } while (token != null)
        } catch (_: Exception) { }
        return bySrc
    }

    /** Calories actives (agrégat Health Connect : au prorata des relevés qui débordent la plage). */
    private suspend fun activeKcal(client: HealthConnectClient, a: Instant, b: Instant, origins: Set<DataOrigin>): Double {
        if (origins.isEmpty() || !b.isAfter(a)) return 0.0
        return try {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(a, b),
                    dataOriginFilter = origins
                )
            )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
        } catch (_: Exception) { 0.0 }
    }

    private fun overlapSec(a1: Instant, a2: Instant, b1: Instant, b2: Instant): Double {
        val s = maxOf(a1, b1)
        val e = minOf(a2, b2)
        return if (e.isAfter(s)) Duration.between(s, e).seconds.toDouble() else 0.0
    }

    /** BMR 24 h : dernière pesée Xiaomi S400 (enregistrée par l'onglet Poids). Pair(valeur, vient de la balance) */
    private fun getBMRFromPref(): Pair<Double, Boolean> {
        val v = try {
            activity.getSharedPreferences("callisthenie", ComponentActivity.MODE_PRIVATE)
                .getString("bmrXiaomi", null)?.toDoubleOrNull()
        } catch (_: Exception) { null }
        return if (v != null && v > 1000) Pair(v, true) else Pair(1867.0, false)
    }

    // ───────── Corps, sommeil, cœur ─────────

    private suspend fun readBody(client: HealthConnectClient, granted: Set<String>, out: JSONObject, now: Instant) {
        val last30 = TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now)
        if (has(granted, "READ_WEIGHT")) safe {
            val arr = JSONArray()
            client.readRecords(ReadRecordsRequest(WeightRecord::class, last30, ascendingOrder = true, pageSize = 500))
                .records.forEach { arr.put(JSONObject().put("t", it.time.toString()).put("kg", round1(it.weight.inKilograms))) }
            out.put("weights", arr)
        }
        if (has(granted, "READ_BODY_FAT")) safe {
            client.readRecords(ReadRecordsRequest(BodyFatRecord::class, last30, ascendingOrder = false, pageSize = 1))
                .records.firstOrNull()?.let {
                    out.put("bodyFat", JSONObject().put("t", it.time.toString()).put("pct", round1(it.percentage.value)))
                }
        }
        if (has(granted, "READ_LEAN_BODY_MASS")) safe {
            client.readRecords(ReadRecordsRequest(LeanBodyMassRecord::class, last30, ascendingOrder = false, pageSize = 1))
                .records.firstOrNull()?.let {
                    out.put("leanMass", JSONObject().put("t", it.time.toString()).put("kg", round1(it.mass.inKilograms)))
                }
        }
        if (has(granted, "READ_VO2_MAX")) safe {
            client.readRecords(ReadRecordsRequest(Vo2MaxRecord::class, last30, ascendingOrder = false, pageSize = 1))
                .records.firstOrNull()?.let {
                    out.put("vo2max", JSONObject().put("t", it.time.toString()).put("v", round1(it.vo2MillilitersPerMinuteKilogram)))
                }
        }
        // Température cutanée (lue aussi par l'onglet Poids) : absolue = référence + dernier écart
        if (has(granted, "READ_SKIN_TEMPERATURE")) safe {
            client.readRecords(
                ReadRecordsRequest(
                    SkinTemperatureRecord::class,
                    TimeRangeFilter.between(now.minus(Duration.ofDays(7)), now),
                    ascendingOrder = false, pageSize = 20
                )
            ).records.firstOrNull { it.deltas.isNotEmpty() || it.baseline != null }?.let { r ->
                val delta = r.deltas.maxByOrNull { it.time }?.delta?.inCelsius
                val base = r.baseline?.inCelsius
                if (base != null) out.put("skinTemp", round1(base + (delta ?: 0.0)))
                if (delta != null) out.put("skinTempDelta", Math.round(delta * 100.0) / 100.0)
                out.put("skinTempTime", r.endTime.toString())
            }
        }
    }

    private suspend fun readSleep(client: HealthConnectClient, granted: Set<String>, out: JSONObject, zone: ZoneId, now: Instant) {
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
                val o = JSONObject()
                    .put("start", it.startTime.toString())
                    .put("end", it.endTime.toString())
                    .put("min", Duration.between(it.startTime, it.endTime).toMinutes())
                stageMinutes(it)?.let { st -> o.put("light", st[0]).put("deep", st[1]).put("rem", st[2]).put("awake", st[3]) }
                arr.put(o)
            }
            out.put("sleep", arr)
        }
        // SpO2 : mesures des 36 dernières heures (nuit)
        if (has(granted, "READ_OXYGEN_SATURATION")) safe {
            val arr = JSONArray()
            client.readRecords(
                ReadRecordsRequest(
                    OxygenSaturationRecord::class,
                    TimeRangeFilter.between(now.minus(Duration.ofHours(36)), now),
                    ascendingOrder = true, pageSize = 1000
                )
            ).records.forEach { arr.put(JSONObject().put("t", it.time.toString()).put("pct", round1(it.percentage.value))) }
            out.put("spo2", arr)
        }
    }

    private suspend fun readHeart(client: HealthConnectClient, granted: Set<String>, out: JSONObject, today: LocalDate, zone: ZoneId, now: Instant) {
        if (has(granted, "READ_HEART_RATE")) safe {
            val res = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_MAX, HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(today.atStartOfDay(zone).toInstant(), now)
                )
            )
            res[HeartRateRecord.BPM_MAX]?.let { out.put("hrMaxToday", it) }
            res[HeartRateRecord.BPM_AVG]?.let { out.put("hrAvgToday", it) }
        }
        // HRV (RMSSD, ms) : dernière valeur + moyenne par jour sur 7 jours
        if (has(granted, "READ_HEART_RATE_VARIABILITY")) safe {
            val from7 = today.minusDays(6)
            val recs = client.readRecords(
                ReadRecordsRequest(
                    HeartRateVariabilityRmssdRecord::class,
                    TimeRangeFilter.between(from7.atStartOfDay(zone).toInstant(), now),
                    ascendingOrder = true, pageSize = 1000
                )
            ).records
            recs.lastOrNull()?.let {
                out.put("hrv", Math.round(it.heartRateVariabilityMillis))
                out.put("hrvTime", it.time.toString())
            }
            val perDay = linkedMapOf<LocalDate, MutableList<Double>>()
            recs.forEach { perDay.getOrPut(it.time.atZone(zone).toLocalDate()) { mutableListOf() }.add(it.heartRateVariabilityMillis) }
            val arr = JSONArray()
            var dd = from7
            while (!dd.isAfter(today)) {
                val o = JSONObject().put("date", dd.toString())
                perDay[dd]?.let { l -> o.put("ms", Math.round(l.average())) }
                arr.put(o)
                dd = dd.plusDays(1)
            }
            out.put("hrvDays", arr)
        }
    }

    // ───────── Utilitaires ─────────

    companion object {
        // Fitbit Air : écrit via Health Connect sous un package « com.android.healthconnect.phone.<id> »
        private const val PKG_FITBIT_PREFIX = "com.android.healthconnect.phone."
        // OnePlus Watch 2R : synchronisée par com.xiaomi.wearable
        private const val PKG_ONEPLUS = "com.xiaomi.wearable"
        // v8.1 — Google Health / Google Fit : intermédiaires qui écrivent les données du Fitbit Air
        private const val PKG_GOOGLE_HEALTH = "com.google.android.apps.healthdata"
        private const val PKG_GOOGLE_FIT = "com.google.android.apps.fitness"
        fun isFitbit(pkg: String): Boolean =
            pkg.startsWith(PKG_FITBIT_PREFIX) ||
            pkg == "com.fitbit.FitbitMobile" ||
            pkg == PKG_GOOGLE_HEALTH ||
            pkg == PKG_GOOGLE_FIT
        fun isOnePlus(pkg: String): Boolean =
            pkg == PKG_ONEPLUS || pkg.contains("xiaomi.wearable") ||
            pkg.contains("oneplus.health") || pkg.contains("heytap.health")
    }

    /** Libellé du type d'exercice */
    private fun getExerciseLabel(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "🎾 Padel/Tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "🏸 Badminton"
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH -> "🎾 Squash"
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "🏓 Tennis de table"
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "🎿 Ski"
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "🏂 Snowboard"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "🚴 Vélo"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "🚴 Vélo d'appartement"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "🥾 Randonnée"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "🏃 Course"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "🏃 Tapis de course"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "🚶 Marche"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "🏊 Natation"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "🏊 Nage eau libre"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "💪 Force"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "💪 Haltères"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "🏋️ Callisthénie"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "🧘 Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "🧘 Pilates / gainage"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "🧘 Étirements"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "🔥 HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "🏃 Elliptique"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "🚣 Rameur"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "🪜 Escaliers"
        else -> "🏃 Activité"
    }

    /** Minutes par phase [léger, profond, paradoxal, éveillé] ; null si la session n'a pas de phases. */
    private fun stageMinutes(r: SleepSessionRecord): LongArray? {
        if (r.stages.isEmpty()) return null
        val m = longArrayOf(0, 0, 0, 0)
        r.stages.forEach { st ->
            val min = Duration.between(st.startTime, st.endTime).toMinutes()
            when (st.stage) {
                SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> m[0] += min
                SleepSessionRecord.STAGE_TYPE_DEEP -> m[1] += min
                SleepSessionRecord.STAGE_TYPE_REM -> m[2] += min
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> m[3] += min
                else -> { }
            }
        }
        return m
    }

    /** Agrégation jour par jour, par tranches de 31 jours (requêtes légères même sur 1 an). */
    private suspend fun <T : Any> daily(
        client: HealthConnectClient,
        byDay: Map<LocalDate, JSONObject>,
        firstDay: LocalDate,
        today: LocalDate,
        nowLdt: LocalDateTime,
        metric: AggregateMetric<T>,
        put: (JSONObject, T) -> Unit
    ) {
        var from = firstDay
        while (!from.isAfter(today)) {
            val next = from.plusDays(31)
            val last = next.isAfter(today)
            val end = if (last) nowLdt else next.atStartOfDay()
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(metric),
                    timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), end),
                    timeRangeSlicer = Period.ofDays(1)
                )
            ).forEach { g ->
                val v = g.result[metric]
                val o = byDay[g.startTime.toLocalDate()]
                if (v != null && o != null) put(o, v)
            }
            if (last) break
            from = next
        }
    }

    /** Les autorisations ont la forme android.permission.health.READ_XXX */
    private fun has(granted: Set<String>, perm: String): Boolean = granted.any { it.endsWith(".$perm") }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Exception) { }
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private fun send(json: JSONObject) {
        activity.runOnUiThread {
            webView.evaluateJavascript("window.onHealthData && window.onHealthData($json);", null)
        }
    }
}
