package com.thierry.programme

import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
// v7.1 — Fitbit Air : HRV, température cutanée, calories actives (séance callisthénie)
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.BodyFatRecord
// v7.6 — sessions sportives (OnePlus Watch 2R : toutes ; Fitbit Air : callisthénie, marche)
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
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
 * Pont Health Connect → JavaScript.
 * Exposé dans la WebView sous le nom « HealthBridge ».
 * Le résultat est renvoyé à la page via la fonction JS onHealthData(json).
 * Doit être créé dans MainActivity.onCreate (enregistrement du lanceur d'autorisations).
 */
class HealthBridge(private val activity: ComponentActivity, private val webView: WebView) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var askedThisSession = false
    /** Fenêtre demandée par la page : "week" (lun → dim), "month" (30 j), "year" (année civile) */
    @Volatile private var period = "week"

    private val corePermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        // v7.1
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        // v7.6
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )
    /** v7.1 — Température cutanée : demandée seulement si Health Connect la prend en charge sur ce téléphone */
    private val skinTempPermission = HealthPermission.getReadPermission(SkinTemperatureRecord::class)
    @Volatile private var permissions: Set<String> = corePermissions

    private fun resolvePermissions(client: HealthConnectClient): Set<String> {
        val skinOk = try {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (_: Throwable) { false }
        permissions = if (skinOk) corePermissions + skinTempPermission else corePermissions
        return permissions
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
        val permissions = resolvePermissions(client)
        val granted = client.permissionController.getGrantedPermissions()
        val out = JSONObject()
        out.put("status", if (granted.isEmpty()) "denied" else "ok")
        out.put("granted", JSONArray(granted.map { it.substringAfterLast('.') }))
        out.put("requested", permissions.size)
        out.put("grantedCount", granted.count { it in permissions })
        out.put("updated", Instant.now().toString())
        if (granted.isEmpty()) return out

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val nowLdt = LocalDateTime.now(zone)
        val now = Instant.now()
        // Fenêtres : semaine fixe (lundi → dimanche), 30 derniers jours, année civile (1er janv. → 31 déc.)
        val firstDay: LocalDate
        val lastDay: LocalDate
        when (period) {
            "year" -> { firstDay = today.withDayOfYear(1); lastDay = today.withDayOfYear(today.lengthOfYear()) }
            "month" -> { firstDay = today.minusDays(29); lastDay = today }
            else -> { firstDay = today.with(DayOfWeek.MONDAY); lastDay = firstDay.plusDays(6) }
        }
        out.put("period", period)
        out.put("from", firstDay.toString())
        out.put("to", lastDay.toString())

        // Série quotidienne sur toute la fenêtre (jours futurs vides) :
        // calories brûlées totales (métabolisme + activité), pas, étages, FC repos
        val days = JSONArray()
        val byDay = linkedMapOf<LocalDate, JSONObject>()
        var d = firstDay
        while (!d.isAfter(lastDay)) {
            val o = JSONObject().put("date", d.toString())
            byDay[d] = o
            days.put(o)
            d = d.plusDays(1)
        }
        if (has(granted, "READ_TOTAL_CALORIES_BURNED")) safe {
            daily(client, byDay, firstDay, today, nowLdt, TotalCaloriesBurnedRecord.ENERGY_TOTAL) { o, v ->
                o.put("kcal", Math.round(v.inKilocalories))
            }
        }
        if (has(granted, "READ_STEPS")) safe {
            daily(client, byDay, firstDay, today, nowLdt, StepsRecord.COUNT_TOTAL) { o, v -> o.put("steps", v) }
        }
        if (has(granted, "READ_FLOORS_CLIMBED")) safe {
            daily(client, byDay, firstDay, today, nowLdt, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL) { o, v -> o.put("floors", Math.round(v)) }
        }
        // FC repos : dernière valeur de chaque jour sur la fenêtre
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
                resp.records.forEach {
                    byDay[it.time.atZone(zone).toLocalDate()]?.put("rhr", it.beatsPerMinute)
                }
                token = resp.pageToken
            } while (token != null)
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
        // v7.4 — Métabolisme de base : plus lu dans Health Connect (géré en JS depuis la dernière pesée Xiaomi S400)
        // Sommeil : sessions des 8 dernières nuits (+ phases v7.1)
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
                val st = stageMinutes(it)
                if (st != null) {
                    o.put("light", st[0]).put("deep", st[1]).put("rem", st[2]).put("awake", st[3])
                }
                arr.put(o)
            }
            out.put("sleep", arr)
            // Phases de la dernière nuit : sessions se terminant le même jour que la plus récente
            recs.lastOrNull()?.let { last ->
                val wake = last.endTime.atZone(zone).toLocalDate()
                val tot = longArrayOf(0, 0, 0, 0)
                var any = false
                recs.filter { it.endTime.atZone(zone).toLocalDate() == wake }.forEach { r ->
                    stageMinutes(r)?.let { st -> any = true; for (i in 0..3) tot[i] += st[i] }
                }
                if (any) {
                    out.put("sleepLight", tot[0]).put("sleepDeep", tot[1])
                        .put("sleepRem", tot[2]).put("sleepAwake", tot[3])
                }
            }
        }
        // FC max du jour
        if (has(granted, "READ_HEART_RATE")) safe {
            val startOfDay = today.atStartOfDay(zone).toInstant()
            val res = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_MAX, HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                )
            )
            res[HeartRateRecord.BPM_MAX]?.let { out.put("hrMaxToday", it) }
            res[HeartRateRecord.BPM_AVG]?.let { out.put("hrAvgToday", it) }
        }
        // VO2 max : dernière valeur sur 30 jours
        if (has(granted, "READ_VO2_MAX")) safe {
            client.readRecords(
                ReadRecordsRequest(Vo2MaxRecord::class, last30, ascendingOrder = false, pageSize = 1)
            ).records.firstOrNull()?.let {
                out.put("vo2max", JSONObject().put("t", it.time.toString())
                    .put("v", round1(it.vo2MillilitersPerMinuteKilogram)))
            }
        }
        // SpO2 : mesures des 36 dernières heures (nuit)
        if (has(granted, "READ_OXYGEN_SATURATION")) safe {
            val recs = client.readRecords(
                ReadRecordsRequest(
                    OxygenSaturationRecord::class,
                    TimeRangeFilter.between(now.minus(Duration.ofHours(36)), now),
                    ascendingOrder = true, pageSize = 1000
                )
            ).records
            val arr = JSONArray()
            recs.forEach { arr.put(JSONObject().put("t", it.time.toString()).put("pct", round1(it.percentage.value))) }
            out.put("spo2", arr)
        }
        // v7.1 — HRV (RMSSD, ms) : dernière valeur + moyenne par jour sur 7 jours
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
        // v7.1 — Température cutanée : dernière mesure sur 7 jours
        // Health Connect stocke une référence (optionnelle) + des écarts : absolue = référence + dernier écart
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
        // v7.1 — Calories actives de la séance callisthénie (aujourd'hui, 7h00 → 8h30)
        if (has(granted, "READ_ACTIVE_CALORIES_BURNED")) safe {
            val sevenAM = today.atTime(7, 0).atZone(zone).toInstant()
            val eightThirtyAM = today.atTime(8, 30).atZone(zone).toInstant()
            if (now.isAfter(sevenAM)) {
                val end = if (now.isBefore(eightThirtyAM)) now else eightThirtyAM
                val res = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(sevenAM, end)
                    )
                )
                res[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let {
                    val k = Math.round(it.inKilocalories)
                    if (k > 0) out.put("callisthenieKcal", k)
                }
            }
        }
        // v7.6 — Ventilation des calories actives du jour : sessions sportives + fond, par montre
        // Filtre données résiduelles (OnePlus) : tout record commencé avant minuit est ignoré
        if (has(granted, "READ_ACTIVE_CALORIES_BURNED")) safe {
            val debutAujourdhui = today.atStartOfDay(zone).toInstant()

            // 1. Calories actives du jour (toutes sources)
            val actives = ArrayList<ActiveCaloriesBurnedRecord>()
            var token: String? = null
            do {
                val resp = client.readRecords(
                    ReadRecordsRequest(
                        ActiveCaloriesBurnedRecord::class,
                        TimeRangeFilter.between(debutAujourdhui, now),
                        ascendingOrder = true, pageSize = 1000, pageToken = token
                    )
                )
                resp.records.forEach { if (!it.startTime.isBefore(debutAujourdhui)) actives.add(it) }
                token = resp.pageToken
            } while (token != null)

            // 2. Sessions sportives du jour (OnePlus : toutes ; Fitbit Air : callisthénie, marche…)
            val sessions = ArrayList<ExerciseSessionRecord>()
            if (has(granted, "READ_EXERCISE")) safe {
                var t2: String? = null
                do {
                    val resp = client.readRecords(
                        ReadRecordsRequest(
                            ExerciseSessionRecord::class,
                            TimeRangeFilter.between(debutAujourdhui, now),
                            ascendingOrder = true, pageSize = 200, pageToken = t2
                        )
                    )
                    resp.records.forEach { if (!it.startTime.isBefore(debutAujourdhui)) sessions.add(it) }
                    t2 = resp.pageToken
                } while (t2 != null)
            }

            // 3. Calories de chaque session = calories actives lues pendant la session, même source
            var calsExOnePlus = 0.0
            var calsExFitbit = 0.0
            val sessionsList = JSONArray()
            for (s in sessions) {
                val pkg = s.metadata.dataOrigin.packageName
                val sStart = s.startTime
                val sEnd = s.endTime
                var sKcal = 0.0

                if (isFitbit(pkg)) {
                    // v7.8 — Fitbit Air écrit les calories de session dans TotalCaloriesBurnedRecord
                    var totalKcal = 0.0
                    client.readRecords(
                        ReadRecordsRequest(
                            TotalCaloriesBurnedRecord::class,
                            TimeRangeFilter.between(sStart, sEnd)
                        )
                    ).records.forEach { r ->
                        if (r.metadata.dataOrigin.packageName == pkg)
                            totalKcal += r.energy.inKilocalories
                    }
                    // TotalCaloriesBurnedRecord inclut le BMR de la session → soustrait (BMR progressif calculé côté JS)
                    val dureeMin = Duration.between(sStart, sEnd).toMinutes()
                    val bmrSession = (getBMRFromPref() / 1440.0) * dureeMin
                    sKcal = maxOf(0.0, totalKcal - bmrSession)
                } else {
                    // OnePlus Watch 2R → ActiveCaloriesBurnedRecord
                    client.readRecords(
                        ReadRecordsRequest(
                            ActiveCaloriesBurnedRecord::class,
                            TimeRangeFilter.between(sStart, sEnd)
                        )
                    ).records.forEach { r ->
                        if (r.metadata.dataOrigin.packageName == pkg)
                            sKcal += r.energy.inKilocalories
                    }
                }
                sessionsList.put(JSONObject()
                    .put("type", getExerciseLabel(s.exerciseType))
                    .put("kcal", Math.round(sKcal))
                    .put("source", if (isOnePlus(pkg)) "oneplus" else "fitbit"))
                if (isOnePlus(pkg)) calsExOnePlus += sKcal
                else if (isFitbit(pkg)) calsExFitbit += sKcal
            }

            // Plages des sessions pour la déduplication du fond
            val sessionRanges = ArrayList<LongArray>()
            for (s in sessions) sessionRanges.add(longArrayOf(s.startTime.epochSecond, s.endTime.epochSecond))

            // 4. Calories de fond = calories actives Fitbit Air hors plages sessions
            // OnePlus ignorée : portée uniquement pour les sessions déclarées,
            // ses données résiduelles (sync tardive) ne sont pas fiables comme fond.
            var fondFitbit = 0.0
            for (r in actives) {
                val pkg = r.metadata.dataOrigin.packageName
                if (isOnePlus(pkg)) continue  // fond OnePlus toujours ignoré
                val rStart = r.startTime.epochSecond
                val rEnd = r.endTime.epochSecond
                val duringSession = sessionRanges.any { rStart >= it[0] && rEnd <= it[1] }
                if (duringSession) continue
                val kcal = r.energy.inKilocalories
                if (isFitbit(pkg)) fondFitbit += kcal
            }

            // BMR et total calculés côté JS (getBMRProgressif)
            val src = JSONObject()
                .put("oneplusExercise", Math.round(calsExOnePlus))
                .put("fitbitExercise", Math.round(calsExFitbit))
                .put("oneplusFond", 0L)  // toujours 0 — données résiduelles non fiables
                .put("fitbitFond", Math.round(fondFitbit))
                .put("totalActives", Math.round(calsExOnePlus + calsExFitbit + fondFitbit))
            out.put("caloriesSources", src)
            out.put("exerciseSessions", sessionsList)
        }
        return out
    }

    companion object {
        // Fitbit Air : écrit via Health Connect sous un package « com.android.healthconnect.phone.<id> »
        // (id propre au téléphone, peut changer après réinstallation) → détection par préfixe
        private const val PKG_FITBIT_PREFIX = "com.android.healthconnect.phone."
        // OnePlus Watch 2R : synchronisée par com.xiaomi.wearable
        private const val PKG_ONEPLUS = "com.xiaomi.wearable"
        fun isFitbit(pkg: String): Boolean = pkg.startsWith(PKG_FITBIT_PREFIX) || pkg == "com.fitbit.FitbitMobile"
        fun isOnePlus(pkg: String): Boolean =
            pkg == PKG_ONEPLUS || pkg.contains("xiaomi.wearable") ||
            pkg.contains("oneplus.health") || pkg.contains("heytap.health")
    }

    /** v7.8 — BMR 24 h depuis les SharedPreferences (même valeur que bmrXiaomi côté JS) */
    private fun getBMRFromPref(): Double {
        return activity.getSharedPreferences("callisthenie", ComponentActivity.MODE_PRIVATE)
            .getString("bmrXiaomi", "1867")?.toDoubleOrNull() ?: 1867.0
    }

    /** v7.6 — Libellé du type d'exercice (sessions Health Connect) */
    private fun getExerciseLabel(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "🎾 Padel/Tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "🏸 Badminton"
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "🎿 Ski"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "🚴 Vélo"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "🥾 Randonnée"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "🏃 Course"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "🚶 Marche"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "🏊 Natation"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "💪 Force"
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "🏋️ Callisthénie"
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
            webView.evaluateJavascript(
                "window.onHealthData && window.onHealthData($json);", null
            )
        }
    }
}
