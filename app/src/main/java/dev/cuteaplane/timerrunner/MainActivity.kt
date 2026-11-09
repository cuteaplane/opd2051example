package dev.cuteaplane.timerrunner

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Shizuku UserService 最小示例：
 *
 *   App (普通 uid) --binder--> Shizuku UserService (uid 2000, shell)
 *                                      |
 *                                      +--> /data/local/tmp/timer
 *
 * 没用 Shizuku#newProcess：它在 13.1.5 已被标记为待移除，官方推荐 UserService。
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val PERMISSION_CODE = 1001

        const val LOG_PATH = "/data/local/tmp/timer.log"

        // 顺序必须和 res/values/strings.xml 的 ksu_managers 一致
        private val MANAGER_PKGS = arrayOf(
            "me.weishu.kernelsu",   // KernelSU
            "com.sukisu.ultra",     // SukiSU / SukiSU Ultra
            "com.resukisu.resukisu" // BakaSU / ReSukiSU
        )
        private val MANAGER_ASSETS = arrayOf("timer_ksu", "timer_sukisu", "timer_bakasu")
        private val MANAGER_BINS = arrayOf(
            "/data/local/tmp/timer_ksu",
            "/data/local/tmp/timer_sukisu",
            "/data/local/tmp/timer_bakasu"
        )
        // 由 tools/build-timer-variants.sh 生成后填入；
        // bakasu 保留已验证的 timer13（含 banner），ksu/sukisu 为新编译变体
        private val MANAGER_SHA = arrayOf(
            "1cfd6ddeb011a05a62804c9a1a5ffaeef3cd022b6cbc3395367638011780f80f",
            "e78fab2251e73ce414a68a6c17aae42c80d18a1b7a344a340044ab916c32adbd",
            "74234249afcc70c6fba626914d229519ca2ad3ae180c04d7bebbb769713d61cc"
        )

        const val DEFAULT_MANAGER = 2 // 本机是 BakaSU
        const val KEY_MANAGER = "manager_index"

        // 与移植验证环境一致（README "已验证环境"）
        const val EXPECTED_KERNEL = "6.1.157-android14-11-o-gd9a844c1f21f"
        const val EXPECTED_DEVICE = "OPD2501"

        const val CHUNK_SIZE = 256 * 1024
        const val POLL_INTERVAL_MS = 800L
        const val KEY_BOOT_ID = "exploit_boot_id"
        const val PREFS_NAME = "timer_runner"

        private val USAGE_GUIDE = """
            KSU 路线（KernelSU / SukiSU / BakaSU）

            1. 安装你要用的管理器
               KernelSU（me.weishu.kernelsu）
               SukiSU / SukiSU Ultra（com.sukisu.ultra）
               BakaSU / ReSukiSU（com.resukisu.resukisu）
               装好后在 App 顶部「KSU 管理器」下拉里选中它；先不用激活。

            2. 启动 Shizuku 并授权
               Shizuku 用无线调试 / ADB 启动（不要 root 启动）。
               点「① 授权 Shizuku」。
               若权限受限：开发者选项 →「禁止权限监控」，重启 Shizuku。

            3. 预检 + 安装 timer
               点「② 预检」：应看到 uid=2000(shell)、u:r:shell:s0、
               内核 6.1.157-android14-11-o-gd9a844c1f21f。
               点「③ 安装 timer」：按当前管理器安装对应变体。

            4. 运行 exploit（拼写是 exploit）
               点「⑤ EXPLOIT · timer fake（KSU）」。
               内核不匹配会直接拒绝运行。
               输出写到 /data/local/tmp/timer.log。

            5. 系统弹出「安全警告」时
               ✘ 不要点「立即重启」！
               ✔ 点「知道了」
               ✔ 等日志出现 KSU_LOADED
               ✔ 打开所选管理器 → 软重启（Soft Reboot）

            6. 成功
               日志：KSU_LOADED、[ksu] late-load rc=0。
               软重启后验证：
               su -c 'id; getenforce'
               → uid=0(root) ... context=u:r:ksu:s0、Enforcing
               grep '^kernelsu ' /proc/modules → Live

            7. 失败
               出现 KSU_MISSING / parking without reap / cred leak failed：
               硬重启设备 → 重新「③ 安装 timer」→ 从头再来。

            重要
            • 一次开机只跑一次 exploit；软重启不改变 boot_id。
            • 不要 kill park 住的 timer 进程，可能直接 panic。
            • 成功后软重启让 KSU 生效；彻底清理要硬重启。
            • 仅用于你自己的 OPPO Pad Air 5。

            软件完全免费；如果它帮到你了，可以点下方「请我喝奶茶」，蟹蟹～
        """.trimIndent()
    }

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnGrant: Button
    private lateinit var btnHelp: Button
    private lateinit var managerSpinner: Spinner
    private lateinit var btnPreflight: Button
    private lateinit var btnInstall: Button
    private lateinit var btnIds: Button
    private lateinit var btnLeak: Button
    private lateinit var btnRoot: Button
    private lateinit var btnFake: Button
    private lateinit var btnStopPoll: Button
    private lateinit var btnDestroy: Button
    private lateinit var btnDonate: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private var service: IUserService? = null
    private var pendingAction: ((IUserService) -> Unit)? = null
    private var binding = false
    private var bindTimeout: Runnable? = null
    private var poller: Runnable? = null
    private var logOffset = 0
    @Volatile
    private var exploitStarted = false
    private var exploitMode: String? = null
    @Volatile
    private var outcomeHandled = false

    private enum class Outcome { SUCCESS, FAILURE }

    private val serviceArgs: Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name)
    )
        .processNameSuffix("shell")
        .version(2) // UserService 代码变更后递增，让 Shizuku 重建服务进程
        .daemon(true) // exploit 跑起来后 App 退到后台也不销毁服务进程
        .debuggable(BuildConfig.DEBUG)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            bindTimeout?.let { uiHandler.removeCallbacks(it) }
            val svc = IUserService.Stub.asInterface(binder)
            if (svc == null) {
                appendLog("[svc] asInterface 返回 null")
                return
            }
            service = svc
            appendLog("[svc] 已连接")
            io.execute {
                val who = runCatching { svc.whoami() }.getOrDefault("?")
                postLog("[svc] whoami: $who")
            }
            updateUi()
            val action = pendingAction
            pendingAction = null
            action?.invoke(svc)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            service = null
            appendLog("[svc] 断开")
            updateUi()
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                uiHandler.post {
                    appendLog(if (granted) "[shizuku] 已授权" else "[shizuku] 授权被拒绝")
                    updateUi()
                    if (granted) {
                        bindUserService()
                    } else {
                        pendingAction = null
                    }
                }
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        uiHandler.post {
            appendLog("[shizuku] binder 已连接")
            updateUi()
            if (hasPermission()) bindUserService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        uiHandler.post {
            service = null
            binding = false
            appendLog("[shizuku] binder 断开")
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        btnGrant = findViewById(R.id.btnGrant)
        btnHelp = findViewById(R.id.btnHelp)
        managerSpinner = findViewById(R.id.managerSpinner)
        btnPreflight = findViewById(R.id.btnPreflight)
        btnInstall = findViewById(R.id.btnInstall)
        btnIds = findViewById(R.id.btnIds)
        btnLeak = findViewById(R.id.btnLeak)
        btnRoot = findViewById(R.id.btnRoot)
        btnFake = findViewById(R.id.btnFake)
        btnStopPoll = findViewById(R.id.btnStopPoll)
        btnDestroy = findViewById(R.id.btnDestroy)
        btnDonate = findViewById(R.id.btnDonate)

        btnGrant.setOnClickListener { requestPermission() }
        btnHelp.setOnClickListener { showUsageGuide() }
        btnPreflight.setOnClickListener { runPreflight() }
        btnInstall.setOnClickListener { installTimer() }
        btnIds.setOnClickListener { runDiagnostic("ids") }
        btnLeak.setOnClickListener { runDiagnostic("leak") }
        btnRoot.setOnClickListener { startExploit("root") }
        btnFake.setOnClickListener { startExploit("fake") }
        btnStopPoll.setOnClickListener {
            stopPolling()
            appendLog("[poll] 已停止刷新（后台进程不受影响）")
            updateUi()
        }
        btnDestroy.setOnClickListener { destroyUserService() }
        btnDonate.setOnClickListener { showDonateDialog() }

        managerSpinner.setSelection(
            prefs.getInt(KEY_MANAGER, DEFAULT_MANAGER)
                .coerceIn(0, MANAGER_PKGS.size - 1)
        )
        managerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                prefs.edit().putInt(KEY_MANAGER, position).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateUi()
        appendLog("提示：首次使用请点「使用说明」。若 Shizuku 权限受限：开发者选项 →「禁止权限监控」。")
    }

    override fun onStart() {
        super.onStart()
        runCatching { Shizuku.addBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        updateUi()
        if (pingShizuku() && hasPermission()) bindUserService()
    }

    override fun onStop() {
        super.onStop()
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        bindTimeout?.let { uiHandler.removeCallbacks(it) }
        if (!exploitStarted && !isChangingConfigurations) {
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        }
        // exploit 启动后故意不 unbind：timer 可能正 park 着，硬重启是唯一安全清理方式。
    }

    // ---------------------------------------------------------------- Shizuku

    private fun pingShizuku(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun hasPermission(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    private fun requestPermission() {
        if (!pingShizuku()) {
            toast("请先启动 Shizuku（无线调试 / ADB）")
            openShizukuManager()
            return
        }
        if (hasPermission()) {
            bindUserService()
            return
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            toast("Shizuku 版本过低（需要 API v11+）")
            return
        }
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            toast("授权已被拒绝且不再询问，请在 Shizuku 里重置")
            openShizukuManager()
            return
        }
        runCatching { Shizuku.requestPermission(PERMISSION_CODE) }
            .onFailure { toast("请求授权失败: ${it.message}") }
    }

    private fun openShizukuManager() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.manager")
        if (intent == null) {
            toast("未安装 Shizuku")
        } else {
            runCatching { startActivity(intent) }
        }
    }

    private fun bindUserService() {
        if (service != null || binding) return
        if (!pingShizuku() || !hasPermission()) return
        binding = true
        try {
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (t: Throwable) {
            binding = false
            appendLog("[svc] bind 失败: ${t.javaClass.simpleName}: ${t.message}")
            pendingAction = null
            return
        }
        val timeout = object : Runnable {
            override fun run() {
                if (service == null) {
                    binding = false
                    appendLog("[svc] 绑定超时（检查 Shizuku 是否在运行 / 后台限制）")
                    appendLog("      ColorOS/Android 16 若权限被限制：开发者选项 →「禁止权限监控」，重启 Shizuku 再试")
                }
            }
        }
        bindTimeout = timeout
        uiHandler.postDelayed(timeout, 10_000)
    }

    /** 保证拿到已连接的 UserService 再执行 action；必要时自动请求授权 + 绑定。 */
    private fun withService(action: (IUserService) -> Unit) {
        service?.let {
            action(it)
            return
        }
        if (!pingShizuku()) {
            toast("Shizuku 未运行或未连接")
            openShizukuManager()
            return
        }
        pendingAction = action
        if (!hasPermission()) {
            requestPermission()
        } else {
            bindUserService()
        }
    }

    // ---------------------------------------------------------------- 操作

    private fun runPreflight() {
        withService { svc ->
            io.execute {
                postLog(
                    svc.exec(
                        arrayOf(
                            "sh", "-c",
                            "id; cat /proc/self/attr/current 2>/dev/null; uname -r; " +
                                "getprop ro.product.device; getprop ro.build.version.release; " +
                                "cat /proc/sys/kernel/random/boot_id"
                        )
                    )
                )
            }
        }
    }

    private fun selectedManagerIndex(): Int =
        managerSpinner.selectedItemPosition.coerceIn(0, MANAGER_PKGS.size - 1)

    private fun selectedManagerBin(): String = MANAGER_BINS[selectedManagerIndex()]

    private fun installTimer() {
        withService { svc ->
            val index = selectedManagerIndex()
            val asset = MANAGER_ASSETS[index]
            val bin = MANAGER_BINS[index]
            val pkg = MANAGER_PKGS[index]
            val expected = MANAGER_SHA[index]
            io.execute {
                try {
                    // 目标可能被 root 跑过（root-owned 0755），shell 无法写入，先删再建
                    runCatching { svc.exec(arrayOf("rm", "-f", bin)) }
                    var total = 0L
                    assets.open(asset).use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        var first = true
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            svc.writeFile(bin, buffer.copyOf(n), !first)
                            first = false
                            total += n
                        }
                    }
                    svc.chmod(bin, 493) // 0755
                    postLog("[install] $pkg -> $bin（$total 字节）")
                    postLog(svc.exec(arrayOf("ls", "-l", bin)))
                    val hash = svc.exec(arrayOf("sha256sum", bin))
                    postLog(hash)
                    if (expected.isNotEmpty() && !hash.contains(expected)) {
                        postLog("[install] ⚠ sha256 与预期不一致")
                    }
                } catch (t: Throwable) {
                    postLog("[install] 失败: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private fun runDiagnostic(mode: String) {
        val bin = selectedManagerBin()
        withService { svc ->
            io.execute {
                postLog("[run] $bin $mode ...")
                postLog(
                    runCatching { svc.exec(arrayOf(bin, mode)) }
                        .getOrElse { "[run] 调用失败: ${it.message}" }
                )
            }
        }
    }

    private fun startExploit(mode: String) {
        if (exploitStarted) {
            toast("本次开机已经跑过 exploit，请硬重启设备")
            return
        }
        val index = selectedManagerIndex()
        val bin = MANAGER_BINS[index]
        val pkg = MANAGER_PKGS[index]
        withService { svc ->
            io.execute {
                val pre = runCatching {
                    svc.exec(
                        arrayOf(
                            "sh", "-c",
                            "id; uname -r; getprop ro.product.device; " +
                                "cat /proc/sys/kernel/random/boot_id"
                        )
                    )
                }.getOrDefault("[preflight-error]")

                postLog("[preflight]\n$pre")

                val bootId = bootIdFrom(pre)
                val alreadyRan = bootId != null && bootId == prefs.getString(KEY_BOOT_ID, null)
                if (alreadyRan) {
                    postLog("[guard] 本次开机已运行过 exploit（boot_id=$bootId），拒绝重复运行")
                    uiHandler.post { toast("本次开机已经跑过，请硬重启设备") }
                    return@execute
                }

                // 内核不符：硬性拒绝，不进入确认流程
                if (!pre.contains(EXPECTED_KERNEL)) {
                    postLog("[guard] 内核不匹配：要求 $EXPECTED_KERNEL")
                    postLog("[guard] 已拒绝运行（换内核请先重新核对移植常量）")
                    uiHandler.post { toast("内核不匹配，已拒绝运行") }
                    return@execute
                }
                if (!pre.contains(EXPECTED_DEVICE) && !pre.contains("OP6541L1")) {
                    postLog("[guard] ⚠ 机型不是 $EXPECTED_DEVICE / OP6541L1，仅警告")
                }

                val binState = runCatching {
                    svc.exec(
                        arrayOf("sh", "-c", "test -x $bin && echo BIN_OK || echo BIN_MISSING")
                    )
                }.getOrDefault("")
                if (!binState.contains("BIN_OK")) {
                    postLog("[guard] 当前管理器（$pkg）的 timer 未安装：$bin")
                    postLog("[guard] 请先点「③ 安装 timer」")
                    uiHandler.post { toast("请先安装当前管理器的 timer") }
                    return@execute
                }
                postLog("[manager] $pkg -> $bin")

                uiHandler.post { showExploitConfirm(svc, mode, bootId, bin, pkg) }
            }
        }
    }

    private fun showExploitConfirm(
        svc: IUserService,
        mode: String,
        bootId: String?,
        bin: String,
        pkg: String
    ) {
        val label = if (mode == "fake") "KSU late-load" else "一次性 real-cred root"
        val message = buildString {
            append("将运行 $bin")
            if (mode == "fake") append(" fake")
            append("（$label）。\n\n")
            if (mode == "fake") {
                append("管理器：$pkg\n\n")
            }
            append("规则：\n")
            append("• 一次开机只跑一次；失败就硬重启，不要在同一 boot 重试。\n")
            append("• 进程可能 park 驻留，绝对不要 kill，否则可能直接 panic。\n")
            append("• 先保存好未保存的数据。\n")
            append("• 仅用于你自己的 OPPO Pad Air 5。")
            if (mode == "fake") {
                append("\n\n⚠ 运行中系统可能弹出「安全警告」：\n")
                append("点「知道了」，绝对不要点「立即重启」。\n")
                append("日志出现 KSU_LOADED 后，去所选管理器里软重启一次。")
            }
            if (bootId == null) {
                append("\n\n⚠ 读不到 boot_id，无法防止同一 boot 重复运行。")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("确认运行 exploit")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("运行") { _, _ -> runExploit(svc, mode, bootId, bin) }
            .show()
    }

    private fun runExploit(svc: IUserService, mode: String, bootId: String?, bin: String) {
        exploitStarted = true // 先占位防连点；启动失败会回滚
        uiHandler.post { updateUi() }
        val cmd = if (mode == "fake") arrayOf(bin, "fake") else arrayOf(bin)
        io.execute {
            try {
                // 旧日志可能属于 root；execDetached 会尝试 unlink，这里失败也不阻断启动
                runCatching { svc.writeFile(LOG_PATH, ByteArray(0), false) }
                val result = svc.execDetached(cmd, LOG_PATH)
                if (result.startsWith("[exec-error]")) {
                    // exploit 并没有真正跑起来：允许本次 boot 重试
                    exploitStarted = false
                    prefs.edit().remove(KEY_BOOT_ID).apply()
                    postLog("[exploit] 启动失败（未实际运行）: $result")
                    uiHandler.post { updateUi() }
                    return@execute
                }
                exploitMode = mode
                outcomeHandled = false
                logOffset = 0
                if (bootId != null) {
                    prefs.edit().putString(KEY_BOOT_ID, bootId).apply()
                }
                postLog("[exploit] 已启动（$mode）: $result")
                uiHandler.post {
                    updateUi()
                    startPolling()
                }
            } catch (t: Throwable) {
                exploitStarted = false
                prefs.edit().remove(KEY_BOOT_ID).apply()
                postLog("[exploit] 启动失败: ${t.javaClass.simpleName}: ${t.message}")
                uiHandler.post { updateUi() }
            }
        }
    }

    private fun startPolling() {
        stopPolling()
        val runnable = object : Runnable {
            override fun run() {
                val svc = service
                if (svc == null) {
                    appendLog("[poll] service 已断开，停止轮询")
                    updateUi()
                    return
                }
                io.execute {
                    val text = runCatching { svc.readFile(LOG_PATH, 256 * 1024) }
                        .getOrDefault("")
                    if (text.length > logOffset) {
                        val delta = text.substring(logOffset)
                        logOffset = text.length
                        postLog(delta.trimEnd())
                    }
                    if (!outcomeHandled && text.isNotEmpty()) {
                        val outcome = detectOutcome(exploitMode, text)
                        if (outcome != null) {
                            outcomeHandled = true
                            uiHandler.post { showOutcome(outcome, exploitMode) }
                        }
                    }
                }
                poller = this
                uiHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        poller = runnable
        uiHandler.post(runnable)
        updateUi()
    }

    private fun stopPolling() {
        poller?.let { uiHandler.removeCallbacks(it) }
        poller = null
    }

    /**
     * 成功标志先判（fake 成功后的 park 也带 "parking without reap"，不能当失败）。
     * 失败只认终态；像 "no zombie; holding child" 这种是单次尝试，后面可能成功，不算。
     */
    private fun detectOutcome(mode: String?, log: String): Outcome? {
        if (log.contains("KSU_LOADED")) return Outcome.SUCCESS
        if (mode == "root" && log.contains("ROOT_SHELL_OK")) return Outcome.SUCCESS

        if (Regex("late-load rc=[1-9]").containsMatchIn(log)) return Outcome.FAILURE

        val failureMarkers = listOf(
            "parking without reap",
            "KSU_MISSING",
            "ksud missing",
            "cred leak failed",
            "base calibration failed",
            "page event ids not found",
            "child did not reach euid 0"
        )
        if (failureMarkers.any { log.contains(it) }) return Outcome.FAILURE
        return null
    }

    private fun showOutcome(outcome: Outcome, mode: String?) {
        val (title, message) = when {
            outcome == Outcome.SUCCESS && mode == "fake" ->
                "exploit 成功：KSU_LOADED" to
                    "现在去所选的 KSU 管理器（KernelSU / SukiSU / BakaSU）里做一次「软重启」（Soft Reboot），让 KSU 生效。\n\n" +
                    "• 系统若再弹「安全警告」，点「知道了」，不要点「立即重启」。\n" +
                    "• 不要 kill park 住的 timer 进程，也不要卸载 App。\n" +
                    "• 本次开机已完成，App 会拒绝再次运行 exploit；要彻底清理请硬重启。"

            outcome == Outcome.SUCCESS ->
                "exploit 成功：ROOT_SHELL_OK" to
                    "一次性 real-cred root 成功。\n\n" +
                    "本 boot 不要再运行 exploit；如果要 KSU，请硬重启后走 ⑤ timer fake 流程。"

            else ->
                "exploit 失败" to
                    "请硬重启设备后再试。\n\n" +
                    "• 不要在同一 boot 里重试。\n" +
                    "• 不要 kill 已 park 的 timer 进程，可能直接 panic。\n" +
                    "• 硬重启后重新安装 timer、预检，再运行。"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun destroyUserService() {
        if (exploitStarted) {
            toast("exploit 已启动，不要再动 UserService；请硬重启设备")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("结束 UserService")
            .setMessage("会调用 destroy() 退出特权服务进程。诊断阶段可以这样做；exploit 跑过之后不要。")
            .setNegativeButton("取消", null)
            .setPositiveButton("结束") { _, _ ->
                runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
                    .onFailure { toast("unbind 失败: ${it.message}") }
                service = null
                binding = false
                updateUi()
            }
            .show()
    }

    // ---------------------------------------------------------------- UI

    private fun showUsageGuide() {
        val view = layoutInflater.inflate(R.layout.dialog_usage, null)
        view.findViewById<TextView>(R.id.usageText).text = USAGE_GUIDE
        AlertDialog.Builder(this)
            .setTitle("TimerRunner 使用说明")
            .setView(view)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showDonateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_donate, null)
        AlertDialog.Builder(this)
            .setTitle("请我喝奶茶")
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun updateUi() {
        if (!::statusView.isInitialized) return
        val ping = pingShizuku()
        val granted = ping && hasPermission()
        val version = if (ping) runCatching { Shizuku.getVersion() }.getOrDefault(0) else 0
        statusView.text = buildString {
            append("Shizuku: ")
            append(if (ping) "已连接 v$version" else "未运行")
            append("  ·  权限: ")
            append(if (granted) "已授权" else "未授权")
            append("\nUserService: ")
            append(if (service != null) "已连接" else if (binding) "绑定中…" else "未连接")
            append("  ·  exploit: ")
            append(if (exploitStarted) "已启动（重启前勿重试）" else "未启动")
        }
        btnGrant.isEnabled = !granted
        btnPreflight.isEnabled = true
        btnInstall.isEnabled = !exploitStarted
        btnIds.isEnabled = !exploitStarted
        btnLeak.isEnabled = !exploitStarted
        btnRoot.isEnabled = !exploitStarted
        btnFake.isEnabled = !exploitStarted
        managerSpinner.isEnabled = !exploitStarted
        btnStopPoll.isEnabled = poller != null
        btnDestroy.isEnabled = !exploitStarted && service != null
    }

    private fun postLog(message: String) {
        uiHandler.post { appendLog(message) }
    }

    private fun appendLog(message: String) {
        if (message.isEmpty()) return
        logView.append(if (message.endsWith("\n")) message else "$message\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun bootIdFrom(output: String): String? =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            .find(output)?.value
}
