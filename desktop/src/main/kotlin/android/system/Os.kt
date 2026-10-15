package android.system

import java.io.FileDescriptor

/** The file calls CrashLog makes to send native stderr to a file: on the desktop stderr stays where it is (terminal or journal). */
object Os {
    fun open(path: String, flags: Int, mode: Int): FileDescriptor = FileDescriptor()
    fun dup2(fd: FileDescriptor, target: Int): FileDescriptor = fd
    fun close(fd: FileDescriptor) {}
}
object OsConstants { const val O_WRONLY = 1; const val O_CREAT = 64; const val O_APPEND = 1024; const val O_RDONLY = 0 }
