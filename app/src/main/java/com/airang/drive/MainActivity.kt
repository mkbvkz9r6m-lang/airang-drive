package com.airang.drive

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts

private object TripRuntime {
    var gear by mutableStateOf<VehicleGear?>(null)
    var status by mutableStateOf("차량 정보 수신 대기")
    var querying by mutableStateOf(false)
    var checks by mutableStateOf(0)
    var backgroundChecks by mutableStateOf(0)
    var running by mutableStateOf(false)
    var visible by mutableStateOf(false)
    var backgroundAttempts by mutableStateOf(0)
    var backgroundFailures by mutableStateOf(0)
    var backgroundTimeouts by mutableStateOf(0)
    var lastBackgroundError by mutableStateOf("없음")
    var lastReceivedGear by mutableStateOf("아직 없음")
    var receivedAt = 0L
    var lastSuccessAt by mutableStateOf(0L)
    var connectionFailure by mutableStateOf<String?>(null)
    var seenDrive by mutableStateOf(false)
    var pendingArrival by mutableStateOf(false)
}

internal data class ArrivalChecklist(
    val baby: Boolean = false,
    val rearSeat: Boolean = false,
    val belongings: Boolean = false
) {
    val checkedCount: Int get() = listOf(baby, rearSeat, belongings).count { it }
    fun canComplete(parkedAndFresh: Boolean): Boolean = checkedCount == 3 && parkedAndFresh
    fun toggle(index: Int): ArrivalChecklist = when (index) {
        0 -> copy(baby = !baby)
        1 -> copy(rearSeat = !rearSeat)
        2 -> copy(belongings = !belongings)
        else -> this
    }
}

internal data class CompletionRecord(
    val completedAt: Long = 0L,
    val showSummary: Boolean = false
) {
    val hasSummary: Boolean get() = completedAt > 0L && showSummary
    fun finish(now: Long): CompletionRecord {
        require(now > 0L)
        return CompletionRecord(now, true)
    }
    fun prepareNext(): CompletionRecord = copy(showSummary = false)
}

internal data class DeparturePreferences(val mask: Int = 7) {
    fun normalized(): DeparturePreferences = copy(mask = mask and 31)
    fun selected(index: Int): Boolean = index in 0..4 && (mask and (1 shl index)) != 0
    fun toggle(index: Int): DeparturePreferences =
        if (index in 0..4) copy(mask = (mask xor (1 shl index)) and 31) else this
    val selectedIndices: List<Int> get() = (0..4).filter { selected(it) }
}

internal enum class VehicleLinkState { WAITING, CHECKING, RECEIVING, DELAYED, FAILED, STOPPED }

internal fun vehicleLinkState(
    activeTrip: Boolean,
    monitorRunning: Boolean,
    querying: Boolean,
    knownGear: Boolean,
    lastSuccessAt: Long,
    now: Long,
    failed: Boolean
): VehicleLinkState {
    if (activeTrip && !monitorRunning) return VehicleLinkState.STOPPED
    if (failed) return VehicleLinkState.FAILED
    val age = (now - lastSuccessAt).coerceAtLeast(0L)
    if (knownGear && lastSuccessAt > 0L && (!activeTrip || age < 6000L)) return VehicleLinkState.RECEIVING
    if (activeTrip && lastSuccessAt > 0L && age >= 6000L) return VehicleLinkState.DELAYED
    return if (querying) VehicleLinkState.CHECKING else VehicleLinkState.WAITING
}

class MainActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("airang_trip_v2", MODE_PRIVATE) }
    private var babyOnBoard by mutableStateOf(false)
    private var completionRecord by mutableStateOf(CompletionRecord())
    private var departurePreferences by mutableStateOf(DeparturePreferences())
    private var showArrivalCheck by mutableStateOf(false)
    private var showReturnQuestion by mutableStateOf(false)
    private var arrivalChecklist by mutableStateOf(ArrivalChecklist())
    private var needsReturnCheck = false
    private var resumed by mutableStateOf(false)
    private var informationSession by mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* A foreground service may run if notification permission is declined. */ }

    private fun requestRead() {
        if (!resumed) return
        try {
            val intent = Intent(this, TripMonitorService::class.java)
            if (babyOnBoard) startForegroundService(intent) else startService(intent)
        } catch (error: Exception) {
            TripRuntime.gear = null
            TripRuntime.status = "차량 확인 서비스 시작 실패 · ${error.message}"
            TripRuntime.connectionFailure = TripRuntime.status
        }
    }

    private fun clearArrival() {
        TripRuntime.pendingArrival = false
        TripRuntime.seenDrive = false
        preferences.edit().putBoolean("v4_seenDrive", false)
            .putBoolean("v4_pendingArrival", false).apply()
    }

    private fun startTrip() {
        completionRecord = completionRecord.prepareNext()
        arrivalChecklist = ArrivalChecklist()
        clearArrival()
        babyOnBoard = true
        needsReturnCheck = false
        preferences.edit().putBoolean("babyOnBoard", true)
            .putBoolean("v7_showCompletion", false).apply()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        requestRead()
    }

    private fun endTrip() {
        val parkedAndFresh = resumed && TripRuntime.gear == VehicleGear.GEAR_PARK &&
            !TripRuntime.querying && SystemClock.elapsedRealtime() - TripRuntime.receivedAt < 6000L
        if (!babyOnBoard || !showArrivalCheck || !arrivalChecklist.canComplete(parkedAndFresh)) return
        completionRecord = completionRecord.finish(System.currentTimeMillis())
        arrivalChecklist = ArrivalChecklist()
        babyOnBoard = false
        needsReturnCheck = false
        showReturnQuestion = false
        showArrivalCheck = false
        clearArrival()
        preferences.edit().putBoolean("babyOnBoard", false)
            .putLong("v7_completedAt", completionRecord.completedAt)
            .putBoolean("v7_showCompletion", true).apply()
        stopService(Intent(this, TripMonitorService::class.java))
    }

    private fun saveDeparturePreferences(value: DeparturePreferences) {
        if (babyOnBoard) return
        departurePreferences = value.normalized()
        preferences.edit().putInt("v8_departureMask", departurePreferences.mask).apply()
    }

    private fun prepareNextTrip() {
        if (babyOnBoard) return
        completionRecord = completionRecord.prepareNext()
        preferences.edit().putBoolean("v7_showCompletion", false).apply()
    }

    private fun continueTrip() {
        arrivalChecklist = ArrivalChecklist()
        needsReturnCheck = false
        showReturnQuestion = false
        clearArrival()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        TripRuntime.visible = hasWindowFocus()
        // Never display an arrival dialog using the last P before leaving the screen.
        TripRuntime.gear = null
        requestRead()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        TripRuntime.visible = resumed && hasFocus
    }

    override fun onPause() {
        informationSession++
        resumed = false
        TripRuntime.visible = false
        if (babyOnBoard) needsReturnCheck = true
        showReturnQuestion = false
        showArrivalCheck = false
        arrivalChecklist = ArrivalChecklist()
        // TripMonitorService owns the SDK; leaving this Activity does not stop it.
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        babyOnBoard = preferences.getBoolean("babyOnBoard", false)
        departurePreferences = DeparturePreferences(preferences.getInt("v8_departureMask", 7)).normalized()
        completionRecord = CompletionRecord(
            preferences.getLong("v7_completedAt", 0L),
            preferences.getBoolean("v7_showCompletion", false) && !babyOnBoard
        )
        needsReturnCheck = babyOnBoard
        TripRuntime.seenDrive = babyOnBoard && preferences.getBoolean("v4_seenDrive", false)
        TripRuntime.pendingArrival = babyOnBoard && preferences.getBoolean("v4_pendingArrival", false)
        setContent {
            val gear = TripRuntime.gear
            val now by produceState(initialValue = SystemClock.elapsedRealtime(), key1 = TripRuntime.lastSuccessAt) {
                value = SystemClock.elapsedRealtime()
                kotlinx.coroutines.delay(6001L)
                value = SystemClock.elapsedRealtime()
            }
            val freshPark = gear == VehicleGear.GEAR_PARK && !TripRuntime.querying &&
                TripRuntime.lastSuccessAt > 0L && (now - TripRuntime.lastSuccessAt).coerceAtLeast(0L) < 6000L
            val connectionState = vehicleLinkState(
                babyOnBoard, TripRuntime.running, TripRuntime.querying, gear != null,
                TripRuntime.lastSuccessAt, now, TripRuntime.connectionFailure != null
            )
            LaunchedEffect(gear, TripRuntime.querying, TripRuntime.pendingArrival, resumed, freshPark) {
                if (!resumed || gear != VehicleGear.GEAR_PARK) {
                    showReturnQuestion = false
                    showArrivalCheck = false
                    arrivalChecklist = ArrivalChecklist()
                } else if (freshPark && babyOnBoard && !showArrivalCheck &&
                    (needsReturnCheck || TripRuntime.pendingArrival)) {
                    showReturnQuestion = true
                }
            }
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF167568), background = Color(0xFFF3F7F6), surface = Color.White
            )) {
                DriveHome(
                    appResumed = resumed,
                    informationSession = informationSession,
                    vehicleStatus = TripRuntime.status,
                    connectionState = connectionState,
                    queryRunning = TripRuntime.querying,
                    onReadGear = ::requestRead,
                    automaticChecks = TripRuntime.checks,
                    babyOnBoard = babyOnBoard,
                    completionRecord = completionRecord,
                    departurePreferences = departurePreferences,
                    onSaveDeparturePreferences = ::saveDeparturePreferences,
                    onPrepareNextTrip = ::prepareNextTrip,
                    showArrivalCheck = showArrivalCheck,
                    showReturnQuestion = showReturnQuestion,
                    canCheckArrival = freshPark,
                    onStartTrip = ::startTrip,
                    arrivalChecklist = arrivalChecklist,
                    onToggleArrivalItem = { index ->
                        if (showArrivalCheck && freshPark) arrivalChecklist = arrivalChecklist.toggle(index)
                    },
                    onOpenArrival = {
                        if (freshPark) {
                            arrivalChecklist = ArrivalChecklist()
                            showArrivalCheck = true
                        }
                    },
                    onCloseArrival = {
                        showArrivalCheck = false
                        arrivalChecklist = ArrivalChecklist()
                    },
                    onEndTrip = ::endTrip,
                    onContinueTrip = ::continueTrip,
                    onConfirmReturn = {
                        if (freshPark) {
                            needsReturnCheck = false
                            showReturnQuestion = false
                            clearArrival()
                            arrivalChecklist = ArrivalChecklist()
                            showArrivalCheck = true
                        }
                    }
                )
            }
        }
    }
}

/** Started by the visible Activity, only promoted to foreground during an active trip. */
class TripMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("airang_trip_v2", MODE_PRIVATE) }
    private var vehicle: Vehicle? = null
    private var sequence = 0
    private var destroyed = false
    private var timeout: Runnable? = null
    private var foreground = false
    private val tripActive get() = preferences.getBoolean("babyOnBoard", false)
    private val poll = object : Runnable {
        override fun run() {
            if (destroyed || !tripActive) return
            if (!TripRuntime.querying) readGear()
            handler.postDelayed(this, 2000L)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tripActive) {
            try {
                if (!foreground) {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel(
                        "airang_trip", "아이랑 이동 상태 확인", NotificationManager.IMPORTANCE_LOW
                    ))
                    val open = PendingIntent.getActivity(this, 0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val notification = Notification.Builder(this, "airang_trip")
                        .setSmallIcon(android.R.drawable.ic_menu_compass)
                        .setContentTitle("아이랑 드라이브 · 이동 중")
                        .setContentText("기어를 확인하고 있어요. 도착 후 앱에서 뒷좌석을 확인해주세요.")
                        .setContentIntent(open).setOngoing(true).build()
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(4104, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else startForeground(4104, notification)
                    foreground = true
                }
                TripRuntime.running = true
                handler.removeCallbacks(poll)
                handler.postDelayed(poll, 2000L)
            } catch (error: Exception) {
                TripRuntime.status = "화면 밖 확인 시작 실패 · ${error.message}"
                TripRuntime.connectionFailure = TripRuntime.status
                TripRuntime.gear = null
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!TripRuntime.querying) readGear()
        // Force stop or OS termination does not silently restart an old trip monitor.
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readGear() {
        if (destroyed || TripRuntime.querying) return
        val id = ++sequence
        TripRuntime.querying = true
        TripRuntime.checks++
        val requestedWhileHidden = !TripRuntime.visible
        if (requestedWhileHidden) TripRuntime.backgroundAttempts++
        val expiry = Runnable {
            if (id == sequence && TripRuntime.querying) {
                sequence++
                TripRuntime.querying = false
                TripRuntime.gear = null
                TripRuntime.status = "차량 응답 시간 초과 · 다음 조회에서 재시도합니다"
                TripRuntime.connectionFailure = TripRuntime.status
                if (requestedWhileHidden) {
                    TripRuntime.backgroundTimeouts++
                    TripRuntime.lastBackgroundError = "응답 시간 초과 (10초)"
                }
                releaseVehicle()
                if (!tripActive) stopSelf()
            }
        }
        timeout = expiry
        handler.postDelayed(expiry, 10000L)
        fun finish(value: VehicleGear?, error: String? = null) {
            handler.post {
                if (destroyed || id != sequence || !TripRuntime.querying) return@post
                handler.removeCallbacks(expiry)
                timeout = null
                TripRuntime.querying = false
                val label = when(value) {
                    VehicleGear.GEAR_PARK -> "P · 주차"
                    VehicleGear.GEAR_DRIVE -> "D · 주행"
                    VehicleGear.GEAR_REVERSE -> "R · 후진"
                    VehicleGear.GEAR_NEUTRAL -> "N · 중립"
                    else -> null
                }
                if (label != null) {
                    TripRuntime.lastReceivedGear = label
                    TripRuntime.lastSuccessAt = SystemClock.elapsedRealtime()
                    TripRuntime.connectionFailure = null
                } else {
                    TripRuntime.connectionFailure = error ?: "주차 여부 판정 불가 · ${value?.name ?: "값 없음"}"
                }
                if (requestedWhileHidden) {
                    if (label != null) TripRuntime.backgroundChecks++
                    else {
                        TripRuntime.backgroundFailures++
                        TripRuntime.lastBackgroundError = error ?: "알 수 없는 기어 값: ${value?.name ?: "없음"}"
                    }
                }
                TripRuntime.gear = if (label != null) value else null
                TripRuntime.receivedAt = SystemClock.elapsedRealtime()
                TripRuntime.status = if (label != null) "기어 정보 수신 완료 · $label"
                    else error ?: "주차 여부 판정 불가 · ${value?.name ?: "값 없음"}"
                if (tripActive && label != null) {
                    if (value == VehicleGear.GEAR_DRIVE || value == VehicleGear.GEAR_REVERSE) {
                        TripRuntime.seenDrive = true
                        TripRuntime.pendingArrival = false
                    } else if (value == VehicleGear.GEAR_PARK && TripRuntime.seenDrive) {
                        TripRuntime.pendingArrival = true
                    } else if (value != VehicleGear.GEAR_PARK) {
                        TripRuntime.pendingArrival = false
                    }
                    preferences.edit().putBoolean("v4_seenDrive", TripRuntime.seenDrive)
                        .putBoolean("v4_pendingArrival", TripRuntime.pendingArrival).apply()
                }
                if (label == null) releaseVehicle()
                if (!tripActive) stopSelf()
            }
        }
        try {
            val client = vehicle ?: Vehicle(applicationContext).also {
                vehicle = it
                it.initialize()
            }
            client.getGear().getCurrentGearSelectionState(
                onComplete = { finish(it) },
                onFailed = { finish(null, "차량 조회 실패 · ${it.javaClass.simpleName}: ${it.message}") }
            )
        } catch (error: Exception) {
            finish(null, "차량 연결 실패 · ${error.message}")
        }
    }

    private fun releaseVehicle() {
        val old = vehicle
        vehicle = null
        try { old?.release() } catch (_: Exception) { }
    }
    override fun onDestroy() {
        destroyed = true
        sequence++
        handler.removeCallbacksAndMessages(null)
        timeout = null
        TripRuntime.querying = false
        TripRuntime.running = false
        releaseVehicle()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

private enum class InformationPanel { HELP, ABOUT }

@Composable
private fun DriveHome(
    appResumed: Boolean,
    informationSession: Int,
    vehicleStatus: String,
    connectionState: VehicleLinkState,
    queryRunning: Boolean,
    onReadGear: () -> Unit,
    automaticChecks: Int,
    babyOnBoard: Boolean,
    completionRecord: CompletionRecord,
    departurePreferences: DeparturePreferences,
    onSaveDeparturePreferences: (DeparturePreferences) -> Unit,
    onPrepareNextTrip: () -> Unit,
    showArrivalCheck: Boolean,
    showReturnQuestion: Boolean,
    canCheckArrival: Boolean,
    arrivalChecklist: ArrivalChecklist,
    onToggleArrivalItem: (Int) -> Unit,
    onStartTrip: () -> Unit,
    onOpenArrival: () -> Unit,
    onCloseArrival: () -> Unit,
    onEndTrip: () -> Unit,
    onContinueTrip: () -> Unit,
    onConfirmReturn: () -> Unit
) {
    var informationPanel by remember(informationSession) { mutableStateOf<InformationPanel?>(null) }
    LaunchedEffect(appResumed, showReturnQuestion, showArrivalCheck) {
        if (!appResumed || showReturnQuestion || showArrivalCheck) informationPanel = null
    }
    var showDiagnostics by remember { mutableStateOf(false) }
    var previewConnectionError by remember { mutableStateOf(false) }
    var showDepartureSettings by remember { mutableStateOf(false) }
    var departureDraft by remember { mutableStateOf(departurePreferences) }
    val departureLabels = listOf("카시트와 안전벨트", "기저귀·물티슈", "수유·간식 용품", "여벌 옷", "유모차")
    val ink = Color(0xFF173C36)
    val muted = Color(0xFF526A65)
    val arrivalReady = babyOnBoard && TripRuntime.pendingArrival && canCheckArrival
    val completed = !babyOnBoard && completionRecord.hasSummary
    val completedTime = if (completionRecord.completedAt > 0L) {
        java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.KOREA)
            .format(java.util.Date(completionRecord.completedAt))
    } else ""
    val stage = if (completed) 2 else if (!babyOnBoard) 0 else if (arrivalReady || showArrivalCheck) 2 else 1

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF3F7F6)).padding(24.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.widthIn(max = 1120.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("아이랑 드라이브", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = ink)
                    Text("아기와 함께, 출발부터 내리는 순간까지", fontSize = 22.sp, color = muted)
                }
                Surface(color = Color.White, shape = MaterialTheme.shapes.medium) {
                    Text("v12", modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        fontSize = 20.sp, color = muted)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { informationPanel = InformationPanel.HELP },
                    enabled = !showDepartureSettings && !showReturnQuestion && !showArrivalCheck,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text("사용 방법", fontSize = 22.sp)
                }
                OutlinedButton(onClick = { informationPanel = InformationPanel.ABOUT },
                    enabled = !showDepartureSettings && !showReturnQuestion && !showArrivalCheck,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text("앱 정보", fontSize = 22.sp)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("출발 준비", "이동 중", "도착 확인").forEachIndexed { index, label ->
                    val selected = stage == index
                    Surface(modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                        color = if (selected) Color(0xFF167568) else Color(0xFFE3ECE8)) {
                        Text("${index + 1}  $label", modifier = Modifier.padding(18.dp),
                            fontSize = 24.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.White else muted)
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(if (completed) "하차 확인을 마쳤어요"
                        else if (!babyOnBoard) "함께 떠날 준비가 됐나요?"
                        else if (arrivalReady) "내리기 전, 한 번 더 살펴봐요"
                        else "아기와 함께 이동 중이에요",
                        fontSize = 34.sp, fontWeight = FontWeight.Bold, color = ink)
                    Text(if (completed) "아기 동행 · 뒷좌석 · 아기 짐, 세 항목을 직접 확인하고 이동을 종료했어요."
                        else if (!babyOnBoard) "아기를 태우고 안전벨트와 카시트 고정을 확인한 뒤 이동을 시작해주세요."
                        else if (arrivalReady) "P 기어를 확인했어요. 이동을 마쳤다면 아기와 뒷좌석을 직접 확인해주세요."
                        else "이동을 마치고 P에 두면, 아기와 뒷좌석을 확인할 수 있어요.",
                        fontSize = 24.sp, lineHeight = 34.sp, color = muted)
                    Surface(modifier = Modifier.fillMaxWidth(),
                        color = if (!completed && connectionState in listOf(VehicleLinkState.FAILED, VehicleLinkState.STOPPED, VehicleLinkState.DELAYED))
                            Color(0xFFFFF0DC) else Color(0xFFEDF5F1),
                        shape = MaterialTheme.shapes.medium) {
                        Text(if (completed) "확인 완료 · $completedTime"
                            else when (connectionState) {
                                VehicleLinkState.RECEIVING -> if (babyOnBoard) "차량 정보 수신 확인 · ${TripRuntime.lastReceivedGear}"
                                    else "최근 차량 조회 · ${TripRuntime.lastReceivedGear}"
                                VehicleLinkState.CHECKING -> "차량 정보를 확인하고 있어요"
                                VehicleLinkState.DELAYED -> "최신 차량 정보 수신이 늦어지고 있어요"
                                VehicleLinkState.FAILED -> "차량 정보를 받지 못했어요"
                                VehicleLinkState.STOPPED -> "이동 중 차량 확인이 멈춰 있어요"
                                VehicleLinkState.WAITING -> "차량 정보 확인을 기다리고 있어요"
                            },
                            modifier = Modifier.padding(20.dp), fontSize = 22.sp, color = ink)
                    }
                    if (!completed && connectionState in listOf(VehicleLinkState.FAILED, VehicleLinkState.STOPPED, VehicleLinkState.DELAYED)) {
                        VehicleConnectionNotice(
                            state = connectionState,
                            queryRunning = queryRunning,
                            onRetry = onReadGear
                        )
                    }
                    if (completed) {
                        Text("직접 선택한 체크리스트의 완료 기록이에요. 차량 센서가 아기의 하차를 판정한 기록은 아니에요.",
                            fontSize = 20.sp, lineHeight = 28.sp, color = muted)
                        Button(onClick = onPrepareNextTrip, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)) {
                            Text("다음 이동 준비", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                    } else if (!babyOnBoard) {
                        if (completedTime.isNotEmpty()) {
                            Text("최근 하차 확인 완료 · $completedTime", fontSize = 20.sp, color = muted)
                        }
                        Button(onClick = onStartTrip, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)) {
                            Text("아기와 이동 시작", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(onClick = onOpenArrival, enabled = canCheckArrival,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)) {
                            Text("도착 후 확인하기", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                        if (!canCheckArrival) {
                            Text(if (TripRuntime.gear == null) "주차 기어를 확인하고 있어요. 조회 결과를 기다려주세요."
                                else "P 기어가 확인되면 도착 확인을 사용할 수 있어요.",
                                fontSize = 20.sp, color = muted)
                        }
                    }
                }
            }
            if (!babyOnBoard && !completed) {
                Card(modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("우리 가족 출발 준비 목록", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ink)
                        if (departurePreferences.selectedIndices.isEmpty()) {
                            Text("표시할 준비 항목이 없어요. 필요한 항목을 골라주세요.", fontSize = 22.sp, color = muted)
                        } else {
                            departurePreferences.selectedIndices.forEach { index ->
                                Text("•  ${departureLabels[index]}", fontSize = 24.sp, lineHeight = 34.sp, color = ink)
                            }
                        }
                        Text("선택한 항목을 보여주는 준비 목록이에요. 실제 준비 여부는 직접 확인해주세요.",
                            fontSize = 18.sp, lineHeight = 26.sp, color = muted)
                        OutlinedButton(onClick = {
                            departureDraft = departurePreferences
                            showDepartureSettings = true
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            Text("준비 항목 선택", fontSize = 24.sp)
                        }
                    }
                }
            }
            Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFFE6EFEA),
                shape = MaterialTheme.shapes.medium) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("내리기 전, 기억할 세 가지", fontSize = 24.sp,
                        fontWeight = FontWeight.Bold, color = ink)
                    Text("아기와 함께 내리기   ·   뒷좌석 직접 살피기   ·   아기 짐 챙기기",
                        fontSize = 22.sp, lineHeight = 32.sp, color = ink)
                }
            }
            Text("이 앱은 기어 정보를 이용한 확인 도우미예요. 아기의 탑승이나 하차를 자동으로 감지하지 않아요.",
                fontSize = 18.sp, lineHeight = 26.sp, color = muted)
            TextButton(onClick = { showDiagnostics = !showDiagnostics },
                modifier = Modifier.heightIn(min = 56.dp)) {
                Text(if (showDiagnostics) "차량 연결 확인 접기" else "차량 연결 확인 펼치기", fontSize = 20.sp)
            }
            if (showDiagnostics) {
                Card(modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("연결 확인 · 사용 안내 v12", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ink)
                        Text("앱 버전 ${BuildConfig.VERSION_NAME} · 코드 ${BuildConfig.VERSION_CODE}", fontSize = 20.sp, color = muted)
                        Text(vehicleStatus, fontSize = 22.sp, color = ink)
                        Text("연결 상태: $connectionState", fontSize = 20.sp, color = muted)
                        TextButton(onClick = { previewConnectionError = !previewConnectionError },
                            modifier = Modifier.heightIn(min = 56.dp)) {
                            Text(if (previewConnectionError) "미리보기 접기" else "연결 오류 안내 미리보기", fontSize = 20.sp)
                        }
                        if (previewConnectionError) {
                            Text("미리보기 · 실제 연결 상태와 조회에는 영향을 주지 않아요.", fontSize = 18.sp, color = muted)
                            VehicleConnectionNotice(VehicleLinkState.FAILED, false,
                                onRetry = { previewConnectionError = false }, preview = true)
                        }
                        Text("전체 조회: $automaticChecks\n화면 비활성 조회: ${TripRuntime.backgroundAttempts} · 수신: ${TripRuntime.backgroundChecks} · 실패: ${TripRuntime.backgroundFailures} · 시간초과: ${TripRuntime.backgroundTimeouts}\n최근 수신: ${TripRuntime.lastReceivedGear}\n최근 비활성 오류: ${TripRuntime.lastBackgroundError}",
                            fontSize = 20.sp, lineHeight = 30.sp, color = muted)
                        Text("${if (TripRuntime.running) "서비스 실행 중" else "서비스 대기"} · ${if (TripRuntime.seenDrive) "D 또는 R 수신 기록 있음" else "D 또는 R 수신 기록 없음"} · ${if (TripRuntime.pendingArrival) "P 전환 확인됨" else "도착 전환 대기"}",
                            fontSize = 20.sp, lineHeight = 30.sp, color = muted)
                        OutlinedButton(onClick = onReadGear, enabled = !queryRunning,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            Text(if (queryRunning) "조회 중…" else "현재 기어 다시 조회", fontSize = 22.sp)
                        }
                    }
                }
            }
        }
    }

    if (appResumed && !showReturnQuestion && !showArrivalCheck) {
        when (informationPanel) {
            InformationPanel.HELP -> InformationDialog(
                title = "사용 방법",
                onDismiss = { informationPanel = null }
            ) {
                GuideSection("1. 출발 전에 준비해요", "출발 준비 화면에서 필요한 준비 항목을 고르세요. 카시트와 안전벨트, 실제 준비물은 직접 확인해주세요.")
                GuideSection("2. 이동을 시작해요", "아기를 태운 뒤 ‘아기와 이동 시작’을 누르세요. 이동 확인이 켜지고 차량 정보를 조회해요.")
                GuideSection("3. 주차한 뒤 확인해요", "D 또는 R을 받은 뒤 P가 확인되면 앱으로 돌아왔을 때 이동을 마쳤는지 물어요. 계속 이동한다면 ‘아직 이동 중이에요’를 선택하세요.")
                GuideSection("4. 내리기 전 세 항목을 살펴봐요", "아기와 함께 내리는지, 뒷좌석을 직접 살펴봤는지, 아기 짐을 확인했는지 선택하세요. 세 항목과 최신 P 정보가 확인돼야 이동을 종료할 수 있어요.")
                GuideSection("5. 다음 이동을 준비해요", "완료 시각을 확인한 뒤 ‘다음 이동 준비’를 누르세요. 새 이동은 ‘아기와 이동 시작’을 눌러야 시작해요.")
                GuideSection("연결이 원활하지 않을 때", "P에 주차한 뒤 ‘다시 연결 확인’을 눌러주세요. 자세한 내용은 기본 화면의 ‘차량 연결 확인 펼치기’에서 볼 수 있어요.")
                GuideSection("사용 범위", "아기의 탑승·하차나 방치 여부를 자동 감지하지 않아요. 강제 정지나 차량 전원 종료 중에는 확인을 계속할 수 없어요. 주행 중 사용 제한은 유지돼요.")
            }
            InformationPanel.ABOUT -> InformationDialog(
                title = "앱 정보",
                onDismiss = { informationPanel = null }
            ) {
                GuideSection("아이랑 드라이브", "아기와 함께하는 이동, 출발 준비부터 하차 전 확인까지.")
                GuideSection("버전", "${BuildConfig.VERSION_NAME} · 버전코드 ${BuildConfig.VERSION_CODE}")
                GuideSection("기기에 저장하는 정보", "이동 및 기어 확인 상태, 최근 하차 확인 완료 시각 한 건, 선택한 출발 준비 목록을 앱 내부에 저장해요. 하차 체크리스트의 부분 선택은 저장하지 않아요.")
                GuideSection("기록과 설정", "같은 설치에서 앱을 다시 열거나 업데이트하면 저장한 정보를 이어서 사용해요. 앱 삭제나 데이터 초기화 시에는 지워질 수 있어요. 현재 저장 파일은 클라우드 백업과 다른 기기로의 이전 대상에서 제외돼요.")
                GuideSection("완료 기록의 의미", "체크리스트를 사용자가 직접 확인한 기록이에요. 센서가 실제 아기의 하차를 판정한 기록은 아니에요. 완료 시각은 기기의 날짜·시간 설정을 기준으로 표시해요.")
            }
            null -> Unit
        }
    }

    if (showDepartureSettings && !babyOnBoard && !completed) {
        AlertDialog(
            onDismissRequest = { showDepartureSettings = false },
            title = { Text("출발 전에 볼 항목", fontSize = 30.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("필요한 항목을 선택하고 저장해주세요.", fontSize = 22.sp)
                    departureLabels.forEachIndexed { index, label ->
                        Row(modifier = Modifier.fillMaxWidth()
                            .toggleable(value = departureDraft.selected(index), role = Role.Checkbox,
                                onValueChange = { departureDraft = departureDraft.toggle(index) })
                            .heightIn(min = 64.dp).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Checkbox(checked = departureDraft.selected(index), onCheckedChange = null)
                            Text(label, modifier = Modifier.weight(1f), fontSize = 24.sp, lineHeight = 32.sp)
                        }
                    }
                    Text("이 선택은 목록 설정이며, 준비 완료 표시가 아니에요.", fontSize = 18.sp, lineHeight = 26.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveDeparturePreferences(departureDraft)
                    showDepartureSettings = false
                }, modifier = Modifier.heightIn(min = 64.dp)) { Text("저장", fontSize = 24.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showDepartureSettings = false },
                    modifier = Modifier.heightIn(min = 64.dp)) { Text("취소", fontSize = 24.sp) }
            }
        )
    }

    if (showReturnQuestion) {
        AlertDialog(
            onDismissRequest = onContinueTrip,
            title = { Text("이동을 마치셨나요?", fontSize = 30.sp) },
            text = {
                Text(
                    "${if (TripRuntime.pendingArrival) "D 또는 R 기어를 받은 뒤 P 기어를 확인했어요." else "앱으로 돌아왔고 현재 기어가 P입니다. 주행 기어 전환으로 판단한 안내는 아니에요."}\n이동을 마치셨다면 아기와 뒷좌석을 확인해주세요.",
                    fontSize = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmReturn, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("네 · 내리기 전 확인", fontSize = 24.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = onContinueTrip, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("아직 이동 중이에요", fontSize = 24.sp)
                }
            }
        )
    }

    if (showArrivalCheck) {
        AlertDialog(
            onDismissRequest = onCloseArrival,
            title = { Text("내리기 전 확인", fontSize = 30.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("직접 살펴본 항목을 선택해주세요.", fontSize = 22.sp)
                    Text("${arrivalChecklist.checkedCount} / 3 확인", fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    val labels = listOf("아기와 함께 내리는지 확인했어요", "뒷좌석을 직접 살펴봤어요", "아기 짐을 확인했어요")
                    val values = listOf(arrivalChecklist.baby, arrivalChecklist.rearSeat, arrivalChecklist.belongings)
                    labels.forEachIndexed { index, label ->
                        Row(modifier = Modifier.fillMaxWidth()
                            .toggleable(value = values[index], enabled = canCheckArrival,
                                role = Role.Checkbox, onValueChange = { onToggleArrivalItem(index) })
                            .heightIn(min = 72.dp).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Checkbox(checked = values[index], onCheckedChange = null, enabled = canCheckArrival)
                            Text(label, modifier = Modifier.weight(1f), fontSize = 23.sp, lineHeight = 32.sp)
                        }
                    }
                    Text(if (!canCheckArrival) "P 기어 확인 후 선택할 수 있어요."
                        else if (arrivalChecklist.checkedCount < 3) "세 항목을 모두 확인하면 이동을 종료할 수 있어요."
                        else "세 항목을 확인했어요. 아래 버튼으로 이동을 종료해주세요.",
                        fontSize = 18.sp, lineHeight = 26.sp)
                }
            },
            confirmButton = {
                Button(onClick = onEndTrip,
                    enabled = arrivalChecklist.canComplete(canCheckArrival),
                    modifier = Modifier.heightIn(min = 64.dp)) {
                    Text("확인 완료 · 이동 종료", fontSize = 24.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = onCloseArrival, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text("돌아가기", fontSize = 24.sp)
                }
            }
        )
    }
}

@Composable
private fun VehicleConnectionNotice(
    state: VehicleLinkState,
    queryRunning: Boolean,
    onRetry: () -> Unit,
    preview: Boolean = false
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFFFFF0DC),
        shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when (state) {
                VehicleLinkState.STOPPED -> "차량 확인을 다시 시작해주세요"
                VehicleLinkState.DELAYED -> "이전 기어 값으로 도착을 판단하지 않아요"
                else -> "차량 연결을 다시 확인해주세요"
            }, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF694516))
            Text(if (state == VehicleLinkState.STOPPED)
                    "P에 주차한 뒤 아래 버튼으로 차량 확인을 다시 시작해주세요. 정상 응답을 받으면 이 안내는 사라져요."
                else "P에 주차한 뒤 다시 확인해주세요. 정상 응답을 받으면 이 안내는 사라져요. 이동 확인 서비스가 실행 중이면 자동 재시도도 계속해요.",
                fontSize = 20.sp, lineHeight = 28.sp, color = Color(0xFF694516))
            OutlinedButton(onClick = onRetry, enabled = !queryRunning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(if (preview) "미리보기 닫기" else if (queryRunning) "차량 응답 기다리는 중…" else "다시 연결 확인", fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun InformationDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(22.dp), content = content)
        },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 64.dp)) {
                Text("닫기", fontSize = 24.sp)
            }
        }
    )
}

@Composable
private fun GuideSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173C36))
        Text(body, fontSize = 21.sp, lineHeight = 31.sp, color = Color(0xFF526A65))
    }
}
