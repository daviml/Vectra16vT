package dev.turbodash.app.data

import android.content.Context
import dev.turbodash.core.alerts.AlertConfig
import dev.turbodash.core.dash.DashStyle
import dev.turbodash.core.ini.IniDefinition
import dev.turbodash.core.ini.IniParser
import dev.turbodash.core.tune.Msq
import dev.turbodash.core.tune.Tune
import java.io.File

/**
 * The user's ECU profile: imported INI, optional msq, project symbols, connection target and
 * alert limits. Files live in app-private storage; small settings in SharedPreferences.
 */
class ProfileStore(context: Context) {

    private val dir = File(context.filesDir, "profile").apply { mkdirs() }
    private val iniFile = File(dir, "mainController.ini")
    private val msqFile = File(dir, "tune.msq")
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    val hasIni get() = iniFile.exists()
    val hasMsq get() = msqFile.exists()

    var iniName: String?
        get() = prefs.getString("iniName", null)
        private set(v) = prefs.edit().putString("iniName", v).apply()

    var msqName: String?
        get() = prefs.getString("msqName", null)
        private set(v) = prefs.edit().putString("msqName", v).apply()

    /** Active INI symbols (TunerStudio "Project Properties → ECU settings"). */
    var symbols: Set<String>
        get() = prefs.getStringSet("symbols", null) ?: setOf("CELSIUS")
        set(v) {
            prefs.edit().putStringSet("symbols", v).apply()
            cachedIni = null
        }

    var bluetoothAddress: String?
        get() = prefs.getString("btAddress", null)
        set(v) = prefs.edit().putString("btAddress", v).apply()

    var autoLog: Boolean
        get() = prefs.getBoolean("autoLog", true)
        set(v) = prefs.edit().putBoolean("autoLog", v).apply()

    var dashStyle: DashStyle
        get() = prefs.getString("dashStyle", null)?.let { n -> DashStyle.entries.firstOrNull { it.name == n } } ?: DashStyle.GAUGES
        set(v) = prefs.edit().putString("dashStyle", v.name).apply()

    var disclaimerAccepted: Boolean
        get() = prefs.getBoolean("disclaimer", false)
        set(v) = prefs.edit().putBoolean("disclaimer", v).apply()

    var alertConfig: AlertConfig
        get() {
            val d = AlertConfig()
            fun f(k: String, def: Double) = prefs.getFloat(k, def.toFloat()).toDouble()
            return d.copy(
                matWarnC = f("matWarn", d.matWarnC),
                matCritC = f("matCrit", d.matCritC),
                cltWarnC = f("cltWarn", d.cltWarnC),
                cltCritC = f("cltCrit", d.cltCritC),
                leanMinMapKpa = f("leanMap", d.leanMinMapKpa),
                leanMarginLambda = f("leanMargin", d.leanMarginLambda),
                egoSaturationPct = f("egoSat", d.egoSaturationPct),
                dutyWarnPct = f("dutyWarn", d.dutyWarnPct),
                dutyCritPct = f("dutyCrit", d.dutyCritPct),
                overboostKpa = f("overboost", d.overboostKpa ?: 0.0).takeIf { it > 0 },
                fuelMinBar = f("fuelMin", d.fuelMinBar),
            )
        }
        set(c) {
            prefs.edit()
                .putFloat("matWarn", c.matWarnC.toFloat())
                .putFloat("matCrit", c.matCritC.toFloat())
                .putFloat("cltWarn", c.cltWarnC.toFloat())
                .putFloat("cltCrit", c.cltCritC.toFloat())
                .putFloat("leanMap", c.leanMinMapKpa.toFloat())
                .putFloat("leanMargin", c.leanMarginLambda.toFloat())
                .putFloat("egoSat", c.egoSaturationPct.toFloat())
                .putFloat("dutyWarn", c.dutyWarnPct.toFloat())
                .putFloat("dutyCrit", c.dutyCritPct.toFloat())
                .putFloat("overboost", (c.overboostKpa ?: 0.0).toFloat())
                .putFloat("fuelMin", c.fuelMinBar.toFloat())
                .apply()
        }

    @Volatile private var cachedIni: IniDefinition? = null

    fun importIni(name: String, bytes: ByteArray) {
        val text = String(bytes, Charsets.ISO_8859_1)
        // Validate before replacing the current INI.
        val def = IniParser.parse(text, symbols)
        require(def.signature.isNotBlank() && def.outputChannels.isNotEmpty()) { "O arquivo não parece um INI do TunerStudio." }
        iniFile.writeBytes(bytes)
        iniName = name
        cachedIni = null
    }

    /** Copies a profile shipped inside the app (trusted, no validation: keeps app start fast). */
    fun installBundled(iniBytes: ByteArray, msqBytes: ByteArray?) {
        iniFile.writeBytes(iniBytes)
        iniName = "mainController.ini"
        cachedIni = null
        if (msqBytes != null) {
            msqFile.writeBytes(msqBytes)
            msqName = "Calibração de exemplo.msq"
        }
    }

    fun importMsq(name: String, bytes: ByteArray) {
        Msq.read(bytes.inputStream()) // validate
        msqFile.writeBytes(bytes)
        msqName = name
    }

    /**
     * Reads TunerStudio's `project.properties`: ECU settings symbols and the Bluetooth device
     * already used by TunerStudio. Returns a short description of what was applied.
     */
    fun importProjectProperties(bytes: ByteArray): String {
        val text = String(bytes, Charsets.ISO_8859_1)
        val applied = ArrayList<String>()
        Regex("(?m)^ecuSettings=(.*)$").find(text)?.groupValues?.get(1)?.let { line ->
            val syms = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (syms.isNotEmpty()) { symbols = syms; applied += "opções: ${syms.joinToString(", ")}" }
        }
        Regex("\\(([0-9A-Fa-f]{12})\\)").find(text)?.groupValues?.get(1)?.let { hex ->
            val mac = hex.uppercase().chunked(2).joinToString(":")
            bluetoothAddress = mac
            applied += "Bluetooth $mac"
        }
        require(applied.isNotEmpty()) { "Nada reconhecido no project.properties." }
        return applied.joinToString("; ")
    }

    /** Parsed INI for the current symbols (cached). */
    fun ini(): IniDefinition? {
        cachedIni?.let { return it }
        if (!iniFile.exists()) return null
        return IniParser.parse(String(iniFile.readBytes(), Charsets.ISO_8859_1), symbols).also { cachedIni = it }
    }

    /** Symbols this INI understands, for the settings screen. */
    fun knownSymbols(): List<String> = ini()?.referencedSymbols?.sorted() ?: emptyList()

    /** Tune from the imported msq, or null. */
    fun msqTune(ini: IniDefinition): Tune? {
        if (!msqFile.exists()) return null
        val tune = Tune(ini)
        msqFile.inputStream().use { Msq.apply(Msq.read(it), tune) }
        return tune
    }
}
