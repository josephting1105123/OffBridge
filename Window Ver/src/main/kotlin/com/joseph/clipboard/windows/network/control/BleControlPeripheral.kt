package com.joseph.clipboard.windows.network.control

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.joseph.clipboard.windows.os.startDrained
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.UUID

/**
 * BLE GATT **peripheral** for the off-grid QR session — the Windows twin of the phone's
 * `BleControlServer`. Advertises the token-derived session service UUID with the fixed
 * Nordic-UART-style RX/TX characteristics so the phone's existing `BleControlClient`
 * (hardware scan filter on the service UUID) finds and links to the PC unchanged.
 *
 * The WinRT GATT API can't be driven from the JVM, and Windows PowerShell can't subscribe
 * to WinRT events (`Register-ObjectEvent` → "cannot subscribe to Windows RT events"), so the
 * same `ProcessBuilder` pattern as [com.joseph.clipboard.windows.network.HotspotController]
 * launches a hidden PowerShell that COMPILES an embedded C# helper with the in-box .NET
 * Framework `csc.exe` (present wherever PowerShell 5.1 is) and execs it. All GATT logic runs
 * in that exe; this class owns the protocol side: frame ↔ chunk conversion ([GattChunker])
 * and the [ControlChannel] surface the [ControlSession]/controller stack expects.
 *
 * Helper wire protocol (LF-terminated JSON lines, chunk bytes base64):
 *  - helper → Kotlin: `{"ev":"init","peripheral":bool}` · `{"ev":"adv","status":...}` ·
 *    `{"ev":"sub","client":id,"mtu":n}` · `{"ev":"rx","client":id,"d":b64}` ·
 *    `{"ev":"unsub","client":id}` · `{"ev":"err","msg":...}`
 *  - Kotlin → helper: `{"cmd":"tx","d":b64}` (notifies the locked client only).
 * The helper locks to the FIRST subscribed phone and drops chunks from any other
 * (single-client rule, mirroring the phone's `BleControlServer`); it exits on stdin EOF,
 * so a dead JVM or a [close] always brings the advertisement down.
 *
 * Fail-soft: [start] returning false (no adapter, no peripheral role, csc missing, PowerShell
 * blocked, advertising aborted) means the caller simply doesn't set the QR's BLE cap bit and
 * the session behaves exactly like today's QR-credentials-only flow.
 */
class BleControlPeripheral : ControlPeripheral {

    /** A phone subscribed to the TX characteristic (link forming) — reports the ATT MTU. */
    override var onSubscribed: ((mtu: Int) -> Unit)? = null

    override var onMessage: ((ByteArray) -> Unit)? = null
    override var onClosed: (() -> Unit)? = null

    private val gson = Gson()
    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var mtu = DEFAULT_MTU
    @Volatile private var closed = false
    @Volatile private var closedFired = false
    private val tempFiles = mutableListOf<File>()
    private val writeLock = Any()

    // Confined to the reader thread (the only caller of accept()).
    private val reassembler = GattReassembler { frame -> onMessage?.invoke(frame) }

    /**
     * Compiles + launches the helper and blocks (≤ [BLE_START_TIMEOUT_MS]) until the
     * advertisement verdict: true only when the adapter supports the peripheral role AND the
     * advertisement actually reached Started. Call off the UI thread (the QR host session
     * already runs [com.joseph.clipboard.windows.network.HotspotController.start] there —
     * this runs within the same spin-up window).
     */
    override fun start(serviceUuid: UUID): Boolean {
        closed = false
        val cs: File
        val ps1: File
        val exe: File
        try {
            cs = File.createTempFile("cs_blehelper", ".cs").apply { writeText(HELPER_CS) }
            ps1 = File.createTempFile("cs_blehelper", ".ps1").apply { writeText(BOOTSTRAP_PS) }
            exe = File.createTempFile("cs_blehelper", ".exe")
            tempFiles += listOf(cs, ps1, exe)
        } catch (e: Exception) {
            System.err.println("BleControlPeripheral: cannot write helper files: ${e.message}")
            return false
        }

        val proc = try {
            ProcessBuilder(
                "powershell", "-NoProfile", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-File", ps1.absolutePath
            ).apply {
                environment()["CS_BLE_SVC_UUID"] = serviceUuid.toString()
                environment()["CS_BLE_HELPER_CS"] = cs.absolutePath
                environment()["CS_BLE_HELPER_EXE"] = exe.absolutePath
                redirectErrorStream(false)
            }.startDrained("BleControlPeripheral helper")
        } catch (e: Exception) {
            System.err.println("BleControlPeripheral: failed to launch PowerShell: ${e.message}")
            cleanup()
            return false
        }
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))

        // One long-lived reader owns stdout: it resolves the start() verdict, then keeps
        // dispatching sub/rx/unsub events until EOF (helper death) fires onClosed. A capacity-1
        // buffered queue (not SynchronousQueue) so the verdict is stored even if the reader
        // reaches it before start() blocks on poll() — no handoff-timing race.
        val verdict = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
        var verdictDelivered = false
        fun deliver(v: Boolean) {
            if (!verdictDelivered) {
                verdictDelivered = true
                verdict.offer(v)
            }
        }
        Thread {
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    handleLine(line, ::deliver)
                }
            } catch (_: Exception) {
            }
            deliver(false) // EOF before a verdict = startup failure
            fireClosed()
        }.apply { isDaemon = true; name = "ble-helper-reader"; start() }

        val ok = try {
            verdict.poll(BLE_START_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) == true
        } catch (_: InterruptedException) {
            false
        }
        if (!ok) close()
        return ok
    }

    private fun handleLine(line: String, deliver: (Boolean) -> Unit) {
        val ev = try {
            JsonParser.parseString(line).asJsonObject
        } catch (_: Exception) {
            return
        }
        when (ev.get("ev")?.asString) {
            "init" -> if (!ev.get("peripheral").asBoolean) deliver(false)
            "adv" -> deliver(ev.get("status")?.asString == "Started")
            "sub" -> {
                mtu = ev.get("mtu")?.asInt?.coerceAtLeast(MIN_MTU) ?: DEFAULT_MTU
                onSubscribed?.invoke(mtu)
            }
            "rx" -> {
                val chunk = try {
                    Base64.getDecoder().decode(ev.get("d").asString)
                } catch (_: Exception) {
                    return
                }
                reassembler.accept(chunk)
            }
            "unsub" -> fireClosed() // the linked phone dropped the GATT connection
            "err" -> System.err.println("BleControlPeripheral helper: ${ev.get("msg")?.asString}")
        }
    }

    private fun fireClosed() {
        if (closed || closedFired) return
        closedFired = true
        onClosed?.invoke()
        // Helper gone (stdout EOF) or the linked phone unsubscribed: nothing above us is
        // obliged to call close(), so tear down here too — otherwise the compiled exe and
        // its .cs/.ps1 sources outlive the session on every post-start helper death.
        close()
    }

    override fun send(frame: ByteArray) {
        val w = writer ?: return
        if (closed) return
        try {
            synchronized(writeLock) {
                for (chunk in GattChunker.split(frame, mtu)) {
                    val cmd = JsonObject()
                    cmd.addProperty("cmd", "tx")
                    cmd.addProperty("d", Base64.getEncoder().encodeToString(chunk))
                    w.write(gson.toJson(cmd))
                    w.newLine()
                }
                w.flush()
            }
        } catch (_: Exception) {
            // Broken pipe = helper died; the reader thread's EOF fires onClosed.
        }
    }

    /** Kills the helper (the advertisement dies with it). Safe to call repeatedly, any thread. */
    override fun close() {
        closed = true
        val proc = process
        process = null
        writer = null
        if (proc == null) {
            cleanup()
            return
        }
        // Windows won't delete a still-running exe, so deletion must wait for the kill to
        // land — but close() runs on the reader thread and session-teardown paths, so do
        // the wait + delete on a detached thread and never block the caller.
        Thread {
            try {
                proc.destroyForcibly()
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
            cleanup()
        }.apply { isDaemon = true; name = "ble-helper-cleanup"; start() }
    }

    private fun cleanup() {
        synchronized(writeLock) {
            tempFiles.forEach { f -> try { if (!f.delete()) f.deleteOnExit() } catch (_: Exception) {} }
            tempFiles.clear()
        }
    }

    companion object {
        /**
         * Compile (~1.5 s) + adapter init + advertising settle (the helper waits out the
         * transient Aborted→Started quirk for up to 6 s). Runs concurrently with the
         * hotspot's 9 s spin-up, so it never adds dialog latency.
         */
        const val BLE_START_TIMEOUT_MS = 8_000L

        /** ATT default until the phone's requested MTU (247 on Android) arrives via `sub`. */
        private const val DEFAULT_MTU = 23
        private const val MIN_MTU = 23

        /**
         * Bootstrap: locate the in-box .NET Framework compiler + GAC facades, compile the
         * embedded C#, then exec it inheriting this PowerShell's (hidden) stdio. csc writes
         * compile errors to stdout, so no stderr redirection (PS 5.1 NativeCommandError trap).
         */
        private val BOOTSTRAP_PS = """
            ${'$'}ErrorActionPreference = 'Stop'
            function EmitErr(${'$'}msg) {
                ${'$'}j = ${'$'}msg -replace '\\','\\' -replace '"','\"'
                Write-Output ('{"ev":"err","msg":"' + ${'$'}j + '"}')
                [Console]::Out.Flush()
            }
            try {
                ${'$'}fw = Join-Path ${'$'}env:windir 'Microsoft.NET\Framework64\v4.0.30319'
                if (-not (Test-Path (Join-Path ${'$'}fw 'csc.exe'))) {
                    ${'$'}fw = Join-Path ${'$'}env:windir 'Microsoft.NET\Framework\v4.0.30319'
                }
                ${'$'}csc = Join-Path ${'$'}fw 'csc.exe'
                if (-not (Test-Path ${'$'}csc)) { EmitErr 'csc.exe not found'; exit 1 }
                ${'$'}gac = Join-Path ${'$'}env:windir 'Microsoft.Net\assembly\GAC_MSIL'
                ${'$'}sr = Get-ChildItem (Join-Path ${'$'}gac 'System.Runtime') -Recurse -Filter 'System.Runtime.dll' | Select-Object -First 1 -ExpandProperty FullName
                ${'$'}srw = Get-ChildItem (Join-Path ${'$'}gac 'System.Runtime.InteropServices.WindowsRuntime') -Recurse -Filter 'System.Runtime.InteropServices.WindowsRuntime.dll' | Select-Object -First 1 -ExpandProperty FullName
                if (-not ${'$'}sr -or -not ${'$'}srw) { EmitErr 'GAC facades not found'; exit 1 }
                ${'$'}wm = Join-Path ${'$'}env:windir 'System32\WinMetadata'
                ${'$'}cscArgs = @(
                    '/nologo', '/target:exe', "/out:${'$'}env:CS_BLE_HELPER_EXE",
                    "/r:${'$'}wm\Windows.Devices.winmd",
                    "/r:${'$'}wm\Windows.Foundation.winmd",
                    "/r:${'$'}wm\Windows.Storage.winmd",
                    "/r:${'$'}sr", "/r:${'$'}srw",
                    ${'$'}env:CS_BLE_HELPER_CS
                )
                ${'$'}out = & ${'$'}csc ${'$'}cscArgs
                if (${'$'}LASTEXITCODE -ne 0) {
                    EmitErr ('csc failed: ' + ((${'$'}out | Select-Object -First 3) -join ' '))
                    exit 1
                }
            } catch {
                EmitErr ${'$'}_.Exception.Message
                exit 1
            }
            & ${'$'}env:CS_BLE_HELPER_EXE
            exit ${'$'}LASTEXITCODE
        """.trimIndent()

        /**
         * The GATT helper (C# 5 — the in-box Framework compiler). No interop assembly:
         * WinRT awaits use the Completed handler, buffers go through DataReader/DataWriter.
         * Validated end-to-end by the T0 spike (init → adv Started on Win11 26200).
         */
        private val HELPER_CS = """
            using System;
            using System.Threading;
            using Windows.Devices.Bluetooth;
            using Windows.Devices.Bluetooth.GenericAttributeProfile;
            using Windows.Foundation;
            using Windows.Storage.Streams;

            public static class BleGattHelper
            {
                static readonly Guid RxUuid = Guid.Parse("8f6d6b22-4e3c-4a76-9339-1c0e6f44b001");
                static readonly Guid TxUuid = Guid.Parse("8f6d6b22-4e3c-4a76-9339-1c0e6f44b002");
                static readonly object Sync = new object();
                static GattLocalCharacteristic _tx;
                static GattSubscribedClient _locked;
                static string _lockedId;

                static T Await<T>(IAsyncOperation<T> op)
                {
                    var mre = new ManualResetEventSlim();
                    op.Completed = delegate { mre.Set(); };
                    if (!mre.Wait(30000)) throw new TimeoutException("WinRT operation timed out");
                    return op.GetResults();
                }

                static void Emit(string json)
                {
                    lock (Sync) { Console.WriteLine(json); Console.Out.Flush(); }
                }

                static string J(string s)
                {
                    return s == null ? "" : s.Replace("\\", "\\\\").Replace("\"", "\\\"");
                }

                public static int Main()
                {
                    try
                    {
                        var adapter = Await(BluetoothAdapter.GetDefaultAsync());
                        bool peripheral = adapter != null && adapter.IsPeripheralRoleSupported;
                        Emit("{\"ev\":\"init\",\"peripheral\":" + (peripheral ? "true" : "false") + "}");
                        if (!peripheral) { WaitForEof(); return 0; }

                        var spRes = Await(GattServiceProvider.CreateAsync(Guid.Parse(
                            Environment.GetEnvironmentVariable("CS_BLE_SVC_UUID"))));
                        if (spRes.Error != BluetoothError.Success)
                        {
                            Emit("{\"ev\":\"err\",\"msg\":\"service: " + spRes.Error + "\"}");
                            return 1;
                        }
                        var provider = spRes.ServiceProvider;

                        var rxParams = new GattLocalCharacteristicParameters();
                        rxParams.CharacteristicProperties =
                            GattCharacteristicProperties.Write | GattCharacteristicProperties.WriteWithoutResponse;
                        var rxRes = Await(provider.Service.CreateCharacteristicAsync(RxUuid, rxParams));
                        if (rxRes.Error != BluetoothError.Success)
                        {
                            Emit("{\"ev\":\"err\",\"msg\":\"rx char: " + rxRes.Error + "\"}");
                            return 1;
                        }
                        rxRes.Characteristic.WriteRequested += OnWriteRequested;

                        var txParams = new GattLocalCharacteristicParameters();
                        txParams.CharacteristicProperties = GattCharacteristicProperties.Notify;
                        var txRes = Await(provider.Service.CreateCharacteristicAsync(TxUuid, txParams));
                        if (txRes.Error != BluetoothError.Success)
                        {
                            Emit("{\"ev\":\"err\",\"msg\":\"tx char: " + txRes.Error + "\"}");
                            return 1;
                        }
                        _tx = txRes.Characteristic;
                        _tx.SubscribedClientsChanged += OnSubscribedClientsChanged;

                        var advParams = new GattServiceProviderAdvertisingParameters();
                        advParams.IsConnectable = true;
                        advParams.IsDiscoverable = true;
                        provider.StartAdvertising(advParams);
                        // The status machine is quirky: a transient Aborted often precedes Started
                        // (observed on Win11 26200). Only Started is terminal-success; wait out
                        // everything else until the deadline and report whatever it settled on.
                        var deadline = DateTime.UtcNow.AddSeconds(6);
                        while (provider.AdvertisementStatus != GattServiceProviderAdvertisementStatus.Started &&
                               DateTime.UtcNow < deadline)
                        {
                            Thread.Sleep(100);
                        }
                        Emit("{\"ev\":\"adv\",\"status\":\"" + provider.AdvertisementStatus + "\"}");

                        // Main thread: blocking stdin reads; GATT events arrive on WinRT threadpool threads.
                        string line;
                        while ((line = Console.In.ReadLine()) != null) HandleCommand(line);
                        return 0;
                    }
                    catch (Exception e)
                    {
                        Emit("{\"ev\":\"err\",\"msg\":\"" + J(e.Message) + "\"}");
                        return 1;
                    }
                }

                static void WaitForEof()
                {
                    while (Console.In.ReadLine() != null) { }
                }

                static void HandleCommand(string line)
                {
                    try
                    {
                        if (line.IndexOf("\"cmd\":\"tx\"", StringComparison.Ordinal) < 0) return;
                        int i = line.IndexOf("\"d\":\"", StringComparison.Ordinal);
                        if (i < 0) return;
                        int start = i + 5;
                        int end = line.IndexOf('"', start);
                        if (end < 0) return;
                        byte[] data = Convert.FromBase64String(line.Substring(start, end - start));
                        GattSubscribedClient target;
                        var tx = _tx;
                        lock (Sync) { target = _locked; }
                        if (tx == null || target == null) return;
                        var writer = new DataWriter();
                        writer.WriteBytes(data);
                        Await(tx.NotifyValueAsync(writer.DetachBuffer(), target));
                    }
                    catch (Exception e)
                    {
                        Emit("{\"ev\":\"err\",\"msg\":\"tx: " + J(e.Message) + "\"}");
                    }
                }

                static void OnWriteRequested(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
                {
                    var deferral = args.GetDeferral();
                    try
                    {
                        var request = Await(args.GetRequestAsync());
                        var data = new byte[request.Value.Length];
                        DataReader.FromBuffer(request.Value).ReadBytes(data);
                        if (request.Option == GattWriteOption.WriteWithResponse) request.Respond();
                        string client = args.Session != null ? args.Session.DeviceId.Id : "?";
                        lock (Sync)
                        {
                            // Chunks from anyone other than the locked (first-subscribed) client are dropped.
                            if (_lockedId != null && client != _lockedId) return;
                        }
                        Emit("{\"ev\":\"rx\",\"client\":\"" + J(client) + "\",\"d\":\"" + Convert.ToBase64String(data) + "\"}");
                    }
                    catch (Exception e)
                    {
                        Emit("{\"ev\":\"err\",\"msg\":\"write: " + J(e.Message) + "\"}");
                    }
                    finally
                    {
                        deferral.Complete();
                    }
                }

                static void OnSubscribedClientsChanged(GattLocalCharacteristic sender, object args)
                {
                    try
                    {
                        GattSubscribedClient gained = null;
                        bool lostLocked = false;
                        string lostId = null;
                        lock (Sync)
                        {
                            bool lockedStillThere = false;
                            foreach (var c in sender.SubscribedClients)
                            {
                                string id = c.Session.DeviceId.Id;
                                if (_lockedId == null)
                                {
                                    // Lock to the first subscriber for the session's lifetime.
                                    _locked = c;
                                    _lockedId = id;
                                    gained = c;
                                    lockedStillThere = true;
                                }
                                else if (id == _lockedId)
                                {
                                    _locked = c;
                                    lockedStillThere = true;
                                }
                            }
                            if (_lockedId != null && !lockedStillThere)
                            {
                                lostLocked = true;
                                lostId = _lockedId;
                                _locked = null;
                            }
                        }
                        if (gained != null)
                        {
                            Emit("{\"ev\":\"sub\",\"client\":\"" + J(_lockedId) + "\",\"mtu\":" + gained.Session.MaxPduSize + "}");
                        }
                        if (lostLocked)
                        {
                            Emit("{\"ev\":\"unsub\",\"client\":\"" + J(lostId) + "\"}");
                        }
                    }
                    catch (Exception e)
                    {
                        Emit("{\"ev\":\"err\",\"msg\":\"sub: " + J(e.Message) + "\"}");
                    }
                }
            }
        """.trimIndent()
    }
}
