package dev.cuteaplane.timerrunner

import android.content.Context
import android.os.Process
import android.system.Os
import androidx.annotation.Keep
import java.io.File
import java.io.FileOutputStream

/**
 * 由 Shizuku 在特权进程里实例化。
 *
 * - 如果 Shizuku 是用 ADB / 无线调试启动的，这里是 uid=2000、u:r:shell:s0；
 * - 如果 Shizuku 是用 root 启动的，这里是 uid=0（那就根本不需要这个 exploit 了）。
 *
 * 这个类不能引用 Activity / 资源 / 单例 UI 状态：它运行在 Shizuku 的进程里。
 */
@Keep
class UserService : IUserService.Stub {

    @Keep
    constructor() : super()

    /** Shizuku v13+ 会优先尝试这个构造器；加 @Keep 防止 R8 删掉。 */
    @Keep
    constructor(context: Context) : super()

    /** Shizuku 服务端保留的事务号，unbind(remove=true) 时调用。 */
    override fun destroy() {
        Process.killProcess(Process.myPid())
        System.exit(0)
    }

    /** 同步执行并收集输出。只用于会退出的命令（ids / leak / 预检）。 */
    override fun exec(cmd: Array<String>): String {
        return try {
            val process = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            "[service uid=${Process.myUid()} pid=${Process.myPid()} exit=$exit]\n$output"
        } catch (t: Throwable) {
            "[exec-error] ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 启动后立即返回；stdout/stderr 全部写到 logPath。会 park 的 exploit 必须走这里。 */
    override fun execDetached(cmd: Array<String>, logPath: String): String {
        return try {
            val logFile = File(logPath)
            // 上一次如果是在 root 下跑的，日志可能是 root:root 0644。
            // /data/local/tmp 对 shell 可写，直接 unlink 再让本次重定向新建。
            if (logFile.exists() && !logFile.canWrite()) {
                logFile.delete()
            }
            ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()
            "started log=$logPath"
        } catch (t: Throwable) {
            "[exec-error] ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    override fun writeFile(path: String, data: ByteArray, append: Boolean) {
        FileOutputStream(path, append).use { it.write(data) }
    }

    override fun chmod(path: String, mode: Int) {
        Os.chmod(path, mode)
    }

    override fun readFile(path: String, maxBytes: Int): String {
        val file = File(path)
        if (!file.isFile) return ""
        val length = minOf(file.length(), maxBytes.toLong()).toInt()
        if (length <= 0) return ""
        return file.inputStream().use { input ->
            val buffer = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(buffer, offset, length - offset)
                if (n < 0) break
                offset += n
            }
            String(buffer, 0, offset, Charsets.UTF_8)
        }
    }

    override fun whoami(): String {
        val context = try {
            File("/proc/self/attr/current").readText().trim()
        } catch (t: Throwable) {
            "?"
        }
        return "uid=${Process.myUid()} pid=${Process.myPid()} selinux=$context"
    }
}
