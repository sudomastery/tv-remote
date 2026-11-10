package com.sudomastery.atv

import com.sudomastery.atv.proto.RemoteProto.RemoteKeyCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Desktop harness for checking the protocol against a real TV.
 *
 *   ./gradlew -q :atvremote:cli --args="<host> pair"              then type the code shown on the TV
 *   ./gradlew -q :atvremote:cli --args="<host> info"
 *   ./gradlew -q :atvremote:cli --args="<host> key DPAD_DOWN DPAD_UP"
 *   ./gradlew -q :atvremote:cli --args="<host> app com.netflix.ninja"
 *   ./gradlew -q :atvremote:cli --args="<host> text hello"
 *
 * Identity and pin are kept in `.atv/` (git-ignored). Set ATV_FRESH=1 to use a throwaway identity.
 */
fun main(args: Array<String>) = runBlocking {
    val host = args.getOrNull(0) ?: error("usage: <host> <pair|info|key|app|text> ...")
    val dir = File(".atv").apply { mkdirs() }
    val keyFile = File(dir, "client.key")
    val certFile = File(dir, "client.crt")
    val pinFile = File(dir, "$host.pin")

    val fresh = System.getenv("ATV_FRESH") == "1"
    val identity = if (!fresh && keyFile.exists()) {
        ClientIdentity.fromEncoded(keyFile.readBytes(), certFile.readBytes())
    } else {
        ClientIdentity.generate().also {
            if (!fresh) {
                keyFile.writeBytes(it.privateKeyEncoded)
                certFile.writeBytes(it.certificateEncoded)
            }
        }
    }

    when (args.getOrNull(1)) {
        "pair" -> PairingSession(host, identity, "TV Remote CLI").use { session ->
            session.start()
            print("Code shown on the TV: ")
            // ATV_CODE_FILE lets a script supply the code (e.g. after reading a TV screenshot).
            val code = System.getenv("ATV_CODE_FILE")?.let { path ->
                val f = File(path)
                while (!f.exists()) delay(200)
                f.readText().trim()
            } ?: readln()
            val pin = session.finish(code)
            pinFile.writeBytes(pin)
            println("Paired. TV certificate pinned.")
        }

        else -> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val pin = if (fresh) null else pinFile.takeIf { it.exists() }?.readBytes()
            val remote = AtvRemote(host, identity, pin, scope)
            val started = System.nanoTime()
            remote.connect()
            val status = withTimeout(10_000) {
                remote.status.first { it != ConnectionStatus.Connecting && it != ConnectionStatus.Disconnected }
            }
            println("status=$status after ${(System.nanoTime() - started) / 1_000_000} ms")
            if (status != ConnectionStatus.Connected) return@runBlocking
            delay(300) // let volume / app updates arrive
            println("device=${remote.deviceInfo.value} on=${remote.isOn.value} volume=${remote.volume.value}")
            println("app=${remote.currentApp.value} voice=${remote.voiceAvailable.value}")
            val rest = args.drop(2)
            when (args[1]) {
                "key" -> rest.forEach {
                    val name = if (it.startsWith("KEYCODE_")) it else "KEYCODE_$it"
                    println("key $name -> ${remote.sendKey(RemoteKeyCode.valueOf(name))}")
                    delay(400)
                }
                "app" -> println("launch -> ${remote.launchApp(rest.first())}")
                "text" -> println("text -> ${remote.sendText(rest.joinToString(" "))}")
            }
            delay(500)
            println("volume=${remote.volume.value} app=${remote.currentApp.value}")
            remote.disconnect()
        }
    }
}
