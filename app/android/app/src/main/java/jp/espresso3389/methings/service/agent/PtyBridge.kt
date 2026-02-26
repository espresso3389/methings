package jp.espresso3389.methings.service.agent

/**
 * JNI interface to the native PTY bridge (libptybridge.so).
 * Provides forkpty-based terminal sessions on Android.
 */
object PtyBridge {
    init {
        System.loadLibrary("ptybridge")
    }

    /**
     * Create a new PTY session.
     * @return int[2] = {pid, masterFd}, or null on failure
     */
    @JvmStatic
    external fun nativeCreateSession(
        shell: String,
        cwd: String?,
        envArray: Array<String>?,
        rows: Int,
        cols: Int
    ): IntArray?

    /** Resize the terminal window. */
    @JvmStatic
    external fun nativeResize(masterFd: Int, pid: Int, rows: Int, cols: Int)

    /** Non-blocking read from master fd. Returns null if nothing available or EOF. */
    @JvmStatic
    external fun nativeRead(masterFd: Int, bufSize: Int): ByteArray?

    /** Write data to master fd. Returns bytes written or -1 on error. */
    @JvmStatic
    external fun nativeWrite(masterFd: Int, data: ByteArray): Int

    /** Close master fd. */
    @JvmStatic
    external fun nativeClose(masterFd: Int)

    /** Kill the process group. */
    @JvmStatic
    external fun nativeKill(pid: Int, signal: Int)

    /** Wait for process exit. Returns exit code, -1 if still running (noHang), -2 on error. */
    @JvmStatic
    external fun nativeWaitpid(pid: Int, noHang: Boolean): Int
}
