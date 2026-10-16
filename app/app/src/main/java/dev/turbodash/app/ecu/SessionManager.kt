package dev.turbodash.app.ecu

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.turbodash.app.data.ProfileStore
import dev.turbodash.app.service.EcuService
import dev.turbodash.core.alerts.Alert
import dev.turbodash.core.ecu.ChannelDecoder
import dev.turbodash.core.ecu.ChannelRoles
import dev.turbodash.core.ecu.EcuSession
import dev.turbodash.core.ecu.SessionState
import dev.turbodash.core.ecu.Snapshot
import dev.turbodash.core.ini.DatalogEntry
import dev.turbodash.core.ini.IniDefinition
import dev.turbodash.core.ini.OutputChannel
import dev.turbodash.core.log.MslReader
import dev.turbodash.core.log.MslWriter
import dev.turbodash.core.sim.ReplayEngine
import dev.turbodash.core.sim.Scenario
import dev.turbodash.core.sim.SimulatedEcu
import dev.turbodash.core.sim.SyntheticEngine
import dev.turbodash.core.tune.Tune
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the data comes from. */
sealed class Target {
    abstract val label: String

    data class Bluetooth(val address: String, val name: String) : Target() {
        override val label get() = "$name ($address)"
    }
    data class Simulator(val scenario: Scenario) : Target() {
        override val label get() = "Simulador: ${scenario.label}"
    }
    data class Replay(val file: File) : Target() {
        override val label get() = "Replay: ${file.name}"
    }
}

data class TuneInfo(val tune: Tune, val fromEcu: Boolean)

data class LogStatus(val file: File, val rows: Int, val startedAt: Long)

/**
 * App-wide owner of the ECU session. UI observes the flows; the session runs on its own thread.
 */
class SessionManager(private val context: Context, private val store: ProfileStore) {

    private val _state = MutableStateFlow<SessionState?>(null)
    val state: StateFlow<SessionState?> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    private val _tune = MutableStateFlow<TuneInfo?>(null)
    val tune: StateFlow<TuneInfo?> = _tune.asStateFlow()

    private val _log = MutableStateFlow<LogStatus?>(null)
    val log: StateFlow<LogStatus?> = _log.asStateFlow()

    private val _target = MutableStateFlow<Target?>(null)
    val target: StateFlow<Target?> = _target.asStateFlow()

    private val _ini = MutableStateFlow<IniDefinition?>(null)
    val ini: StateFlow<IniDefinition?> = _ini.asStateFlow()

    val logsDir = File(context.filesDir, "logs").apply { mkdirs() }

    private val notifier = AlertNotifier(context)
    val gps = GpsSpeed(context)
    private var session: EcuSession? = null
    private var thread: Thread? = null
    private val logLock = Any()
    private var writer: MslWriter? = null
    private var marks = 0

    val isActive get() = session != null

    /** Loads the INI (and msq tune for the map screen) without connecting. */
    fun refreshProfile() {
        val ini = store.ini()
        _ini.value = ini
        if (session == null) _tune.value = ini?.let { store.msqTune(it) }?.let { TuneInfo(it, fromEcu = false) }
    }

    // Each session gets its own number; callbacks from an older one (still shutting down) are ignored.
    @Volatile private var generation = 0

    @Synchronized
    fun start(target: Target) {
        stop()
        if (dev.turbodash.app.Trial.expired(context)) return // expired demo: no connection, no service
        val ini = store.ini() ?: run {
            _state.value = SessionState.Failed("Importe o INI do firmware em Ajustes.", retrying = false)
            return
        }
        _ini.value = ini
        val msqTune = store.msqTune(ini)
        val opener: () -> dev.turbodash.core.protocol.Transport = when (target) {
            is Target.Bluetooth -> {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ({ BluetoothTransport.open(adapter, target.address) })
            }
            is Target.Simulator -> {
                val simTune = msqTune ?: Tune(ini)
                ({ SimulatedEcu(ini, simTune, SyntheticEngine(simTune, target.scenario)) })
            }
            is Target.Replay -> {
                val simTune = msqTune ?: Tune(ini)
                val data = target.file.reader(Charsets.ISO_8859_1).use { MslReader.read(it) }
                ({ SimulatedEcu(ini, simTune, ReplayEngine(ini, data)) })
            }
        }
        _target.value = target
        val s = EcuSession(
            ini, opener, fallbackTune = msqTune, listener = sessionListener(++generation), alertConfig = store.alertConfig,
        )
        session = s
        _state.value = SessionState.Connecting(1) // immediate feedback on the button press
        thread = Thread({ s.run() }, "ecu-session").apply { start() }
        android.os.Handler(android.os.Looper.getMainLooper()).post { gps.acquire() }
        if (target is Target.Bluetooth) startService()
    }

    @Synchronized
    fun stop() {
        val s = session ?: return
        session = null
        generation++
        s.stop()
        thread?.join(3000)
        thread = null
        stopLog()
        android.os.Handler(android.os.Looper.getMainLooper()).post { gps.release() }
        notifier.clear()
        _alerts.value = emptyList()
        _snapshot.value = null
        _target.value = null
        _state.value = null
        context.stopService(Intent(context, EcuService::class.java))
        refreshProfile()
    }

    fun updateAlertConfig() {
        session?.alertConfig = store.alertConfig
    }

    // ------------------------------------------------------------------ logging

    fun startLog() {
        val s = session ?: return
        synchronized(logLock) {
            if (writer != null) return
            val name = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US).format(Date()) + ".msl"
            val file = File(logsDir, name)
            writer = MslWriter(
                s.ini, _tune.value?.tune, s.channelDecoder(), file.bufferedWriter(Charsets.ISO_8859_1),
                extraColumns = extraLogColumns(s.ini, s.channelDecoder()),
            )
            marks = 0
            _log.value = LogStatus(file, 0, System.currentTimeMillis())
        }
    }

    /**
     * Everything the ECU sends that the INI [Datalog] list leaves out, plus phone GPS speed.
     * Decimals follow each channel's resolution so the file stays compact.
     */
    private fun extraLogColumns(ini: IniDefinition, decoder: ChannelDecoder): List<Pair<DatalogEntry, String>> {
        val already = ini.datalog.map { it.channel }.toSet()
        val friendly = mapOf("fuel" to "Fuel Pressure", "oil" to "Oil Pressure", "deadtime1" to "Injector Dead Time")
        val fixedUnits = mapOf("deadtime1" to "ms") // the INI labels it "s" but the value is in ms
        val raw = ini.outputChannels.values.filter { it.name !in already }.mapNotNull { ch ->
            val format = when (ch) {
                is OutputChannel.Bits -> "%d"
                is OutputChannel.Scalar -> {
                    val scale = kotlin.math.abs(ch.scale.number ?: 0.001)
                    when {
                        scale >= 1.0 -> "%d"
                        scale >= 0.1 -> "%.1f"
                        scale >= 0.01 -> "%.2f"
                        else -> "%.3f"
                    }
                }
                is OutputChannel.Formula -> return@mapNotNull null // derived values; the main ones are already in the INI list
            }
            DatalogEntry(ch.name, friendly[ch.name] ?: ch.name, "float", format, null) to
                (fixedUnits[ch.name] ?: decoder.unitsOf(ch.name))
        }
        return listOf(DatalogEntry("gpsSpeed", "GPS Speed", "float", "%.1f", null) to "km/h") + raw
    }

    fun stopLog() {
        synchronized(logLock) {
            writer?.close()
            writer = null
            _log.value = null
        }
    }

    fun mark() {
        synchronized(logLock) { writer?.mark(++marks) }
    }

    // ------------------------------------------------------------------ session callbacks

    private fun sessionListener(gen: Int) = object : EcuSession.Listener {
        override fun onState(state: SessionState) {
            if (gen != generation) return
            _state.value = state
            if (state is SessionState.Running && store.autoLog && writer == null && _tune.value != null) startLog()
        }

        override fun onTune(tune: Tune, fromEcu: Boolean) {
            if (gen != generation) return
            _tune.value = TuneInfo(tune, fromEcu)
        }

        override fun onFrame(frame: Snapshot, alerts: List<Alert>) {
            if (gen != generation) return
            val speed = gps.current()
            val snapshot = if (speed == null) frame else Snapshot(frame.time, frame.values + ("gpsSpeed" to speed))
            _snapshot.value = snapshot
            if (alerts != _alerts.value) _alerts.value = alerts
            notifier.update(alerts)
            synchronized(logLock) {
                writer?.let { w ->
                    w.append(snapshot)
                    _log.value?.let { _log.value = it.copy(rows = it.rows + 1) }
                }
            }
        }
    }

    private fun startService() {
        val granted = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (granted) ContextCompat.startForegroundService(context, Intent(context, EcuService::class.java))
    }

    fun roles(): ChannelRoles? = _ini.value?.let { ChannelRoles(it) }
}
