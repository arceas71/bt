package app.btchat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Bluetooth Classic (RFCOMM) bridge for index.html.
 *
 * This is plain Android code, not a Median API. It is exposed to the page with
 *     webView.addJavascriptInterface(BtBridge(...), "BTBridge")
 * so the page can call window.BTBridge.xxx(). Every @JavascriptInterface method returns a JSON string.
 * Results that happen later (connected, message received, ...) are pushed back to the page by calling
 * window.onBtEvent(jsonString). The full contract is at the top of index.html and in README.md.
 *
 * One connection at a time. One phone calls listen(), the other calls connect(address).
 * The two phones must already be paired in Android's Bluetooth settings.
 */
@SuppressLint("MissingPermission") // permission is checked by hasPermission() before every Bluetooth call
class BtBridge(
    private val activity: Activity,
    private val web: WebView,
    private val askPermissions: (Array<String>) -> Unit,
    private val keepAwake: (Boolean) -> Unit
) {
    companion object {
        /** Both phones must use the same UUID. Change it only if you change it in both installs. */
        val APP_UUID: UUID = UUID.fromString("7b0c3e52-5c1a-4d0e-9a3b-2f6d8e1c4a90")
        const val SERVICE_NAME = "BtChat"
        const val VERSION = 1
        const val MAX_LINE = 65536
    }

    private val adapter: BluetoothAdapter? =
        (activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val lock = Any()
    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    /** Bumped on every listen/connect/close so threads from an older attempt stay silent. */
    private var session = 0
    private val writer = Executors.newSingleThreadExecutor()

    // ---------- small helpers ----------

    private fun ok(): String = JSONObject().put("ok", true).toString()

    private fun err(code: String, detail: String? = null): String {
        val o = JSONObject().put("ok", false).put("error", code)
        if (detail != null) o.put("detail", detail)
        return o.toString()
    }

    private fun emit(o: JSONObject) {
        val js = "window.onBtEvent&&window.onBtEvent(" + JSONObject.quote(o.toString()) + ")"
        web.post { web.evaluateJavascript(js, null) }
    }

    private fun deviceName(d: BluetoothDevice): String =
        try { d.name ?: d.address } catch (e: SecurityException) { d.address }

    private fun emitState(state: String, peer: BluetoothDevice? = null, detail: String? = null) {
        val o = JSONObject().put("type", "state").put("state", state)
        if (peer != null) {
            o.put("peer", JSONObject().put("name", deviceName(peer)).put("address", peer.address))
        }
        if (detail != null) o.put("detail", detail)
        emit(o)
    }

    private fun setAwake(on: Boolean) {
        activity.runOnUiThread { keepAwake(on) }
    }

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        else emptyArray()

    private fun hasPermission(): Boolean = neededPermissions().all {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isEnabled(a: BluetoothAdapter): Boolean =
        try { a.isEnabled } catch (e: SecurityException) { false }

    /** Returns an error JSON if Bluetooth can't be used right now, otherwise null. */
    private fun precheck(): String? {
        val a = adapter ?: return err("no-bluetooth")
        if (!hasPermission()) return err("permission")
        if (!isEnabled(a)) return err("disabled")
        return null
    }

    private fun newSession(): Int = synchronized(lock) {
        closeQuietly()
        session += 1
        session
    }

    private fun isCurrent(id: Int): Boolean = synchronized(lock) { session == id }

    /** Caller must hold [lock]. */
    private fun closeQuietly() {
        try { server?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        server = null
        socket = null
        out = null
    }

    // ---------- API called from JavaScript ----------

    @JavascriptInterface
    fun info(): String {
        val a = adapter
        return JSONObject()
            .put("ok", true)
            .put("version", VERSION)
            .put("supported", a != null)
            .put("enabled", a != null && isEnabled(a))
            .put("hasPermission", hasPermission())
            .put("sdk", Build.VERSION.SDK_INT)
            .toString()
    }

    @JavascriptInterface
    fun requestPermission(): String {
        val need = neededPermissions()
        if (need.isEmpty() || hasPermission()) {
            emit(JSONObject().put("type", "perm").put("granted", true))
        } else {
            activity.runOnUiThread { askPermissions(need) }
        }
        return ok()
    }

    /** Called by MainActivity when the Android permission dialog closes. */
    fun onPermissionResult(granted: Boolean) {
        emit(JSONObject().put("type", "perm").put("granted", granted))
    }

    @JavascriptInterface
    fun pairedDevices(): String {
        precheck()?.let { return it }
        val a = adapter ?: return err("no-bluetooth")
        return try {
            val arr = JSONArray()
            for (d in a.bondedDevices ?: emptySet<BluetoothDevice>()) {
                arr.put(JSONObject().put("name", deviceName(d)).put("address", d.address))
            }
            JSONObject().put("ok", true).put("devices", arr).toString()
        } catch (e: SecurityException) {
            err("permission")
        }
    }

    /** Host: wait for the other phone to connect. */
    @JavascriptInterface
    fun listen(): String {
        precheck()?.let { return it }
        val a = adapter ?: return err("no-bluetooth")
        val id = newSession()
        val ss: BluetoothServerSocket = try {
            a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
        } catch (e: IOException) {
            return err("listen-failed", e.message)
        } catch (e: SecurityException) {
            return err("permission")
        }
        val stale = synchronized(lock) {
            if (session == id) { server = ss; false } else true
        }
        if (stale) {
            try { ss.close() } catch (_: IOException) {}
            return ok()
        }
        emitState("listening")
        Thread {
            try {
                val s = ss.accept()          // blocks until the other phone connects or we close the server
                try { ss.close() } catch (_: IOException) {}
                attach(s, id)
            } catch (e: IOException) {
                failListen(id, e.message)
            } catch (e: SecurityException) {
                failListen(id, "permission denied")
            }
        }.apply { isDaemon = true }.start()
        return ok()
    }

    /** Join: connect to a paired phone that is hosting. */
    @JavascriptInterface
    fun connect(address: String): String {
        precheck()?.let { return it }
        val a = adapter ?: return err("no-bluetooth")
        val dev: BluetoothDevice = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return err("bad-address")
        }
        val id = newSession()
        emitState("connecting", dev)
        Thread {
            try {
                val s = dev.createRfcommSocketToServiceRecord(APP_UUID)
                val stale = synchronized(lock) {
                    if (session == id) { socket = s; false } else true
                }
                if (stale) {
                    try { s.close() } catch (_: IOException) {}
                    return@Thread
                }
                s.connect()                  // blocks; throws IOException if the other phone is not hosting
                attach(s, id)
            } catch (e: IOException) {
                failConnect(id, dev, e.message)
            } catch (e: SecurityException) {
                failConnect(id, dev, "permission denied")
            }
        }.apply { isDaemon = true }.start()
        return ok()
    }

    /** Queue one line of text. The result comes back later as a 'write' event with the same ref. */
    @JavascriptInterface
    fun send(ref: String, line: String): String {
        val snapshot = synchronized(lock) { Pair(out, session) }
        val o = snapshot.first ?: return err("not-connected")
        val id = snapshot.second
        try {
            writer.execute {
                try {
                    val text = line.replace('\n', ' ').replace('\r', ' ') + "\n"
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    synchronized(o) {
                        o.write(bytes)
                        o.flush()
                    }
                    emit(JSONObject().put("type", "write").put("ref", ref).put("ok", true))
                } catch (e: IOException) {
                    emit(
                        JSONObject().put("type", "write").put("ref", ref).put("ok", false)
                            .put("error", e.message ?: "write failed")
                    )
                    // A failed write means the link is dead: closing the socket makes the read loop report it.
                    synchronized(lock) {
                        if (session == id) {
                            try { socket?.close() } catch (_: IOException) {}
                        }
                    }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            return err("not-connected")
        }
        return ok()
    }

    @JavascriptInterface
    fun close(): String {
        val was = synchronized(lock) {
            val had = socket != null || server != null
            closeQuietly()
            session += 1
            had
        }
        emitState("closed", null, if (was) "You disconnected." else "Stopped.")
        setAwake(false)
        return ok()
    }

    @JavascriptInterface
    fun openBluetoothSettings(): String {
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) {
            }
        }
        return ok()
    }

    /** Call from Activity.onDestroy(). */
    fun shutdown() {
        synchronized(lock) {
            closeQuietly()
            session += 1
        }
        writer.shutdownNow()
    }

    // ---------- connection internals ----------

    private fun failListen(id: Int, why: String?) {
        if (!isCurrent(id)) return
        synchronized(lock) { if (session == id) closeQuietly() }
        emitState("error", null, "Stopped waiting for a connection: " + (why ?: "unknown error"))
    }

    private fun failConnect(id: Int, dev: BluetoothDevice, why: String?) {
        if (!isCurrent(id)) return
        synchronized(lock) { if (session == id) closeQuietly() }
        emitState(
            "error", dev,
            "Could not connect to " + deviceName(dev) + " (" + (why ?: "no details") + "). " +
                "Check that the other phone is hosting and that the two phones are paired."
        )
    }

    private fun attach(s: BluetoothSocket, id: Int) {
        val stream: OutputStream = try {
            s.outputStream
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            failConnect(id, s.remoteDevice, e.message)
            return
        }
        val stale = synchronized(lock) {
            if (session == id) { socket = s; out = stream; false } else true
        }
        if (stale) {
            try { s.close() } catch (_: IOException) {}
            return
        }
        emitState("connected", s.remoteDevice)
        setAwake(true)
        readLoop(s, id)
    }

    private fun readLoop(s: BluetoothSocket, id: Int) {
        var reason = "The other phone closed the connection."
        try {
            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            while (true) {
                val line = readBoundedLine(reader) ?: break
                if (!isCurrent(id)) return
                if (line.isNotEmpty()) {
                    emit(JSONObject().put("type", "line").put("line", line))
                }
            }
        } catch (e: IOException) {
            reason = "Bluetooth link lost (" + (e.message ?: "no details") + ")."
        }
        if (!isCurrent(id)) return           // we closed it ourselves; close() already told the page
        synchronized(lock) { if (session == id) closeQuietly() }
        emitState("closed", null, reason)
        setAwake(false)
    }

    private fun readBoundedLine(r: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = r.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (sb.length >= MAX_LINE) throw IOException("message too large")
            sb.append(c.toChar())
        }
    }
}
