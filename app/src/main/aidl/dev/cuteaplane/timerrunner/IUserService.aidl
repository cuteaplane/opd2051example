package dev.cuteaplane.timerrunner;

interface IUserService {
    // Shizuku 服务端保留的事务号：unbind(remove=true) 时会调用
    void destroy() = 16777114;

    // 同步执行命令，返回 stdout+stderr（适合 ids / leak 这类会退出的命令）
    String exec(in String[] cmd) = 1;

    // 异步启动命令，stdout+stderr 重定向到 logPath，立即返回 pid
    // 适合会 park 驻留的 exploit，绝不能 wait / kill
    String execDetached(in String[] cmd, String logPath) = 2;

    // 写文件（第一次 append=false 截断，之后 append=true 追加）
    void writeFile(String path, in byte[] data, boolean append) = 3;

    // chmod，mode 用十进制，例如 0755 = 493
    void chmod(String path, int mode) = 4;

    // 读日志（最多 maxBytes 字节）
    String readFile(String path, int maxBytes) = 5;

    // 打印服务进程身份（uid / pid / SELinux context）
    String whoami() = 6;
}
