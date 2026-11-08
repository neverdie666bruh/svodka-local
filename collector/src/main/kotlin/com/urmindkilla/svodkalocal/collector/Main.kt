package com.urmindkilla.svodkalocal.collector

import com.urmindkilla.svodkalocal.core.classify.Classifier
import com.urmindkilla.svodkalocal.core.edition.CollectService
import com.urmindkilla.svodkalocal.core.json.Codec
import com.urmindkilla.svodkalocal.core.json.RuleFile
import com.urmindkilla.svodkalocal.core.store.FileDigestStore
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val map = parseArgs(args)
    val dataDir = map["data-dir"] ?: exitWith("нужен --data-dir")
    val sourcesPath = map["sources"] ?: "sources/sources.json"
    val rulesPath = map["rules"] ?: "sources/rules.json"
    val force = map.containsKey("force")
    val rules = Codec.json.decodeFromString<RuleFile>(Path.of(rulesPath).toFile().readText())
    val sources = Codec.sources(Path.of(sourcesPath).toFile().readText())
    val outcome = CollectService(
        transport = JavaFeedTransport(allowLoopback = false),
        classifier = Classifier(rules),
    ).run(FileDigestStore(Path.of(dataDir)), sources, force)
    println(
        "edition=${outcome.editionDate} skipped=${outcome.skipped} partial=${outcome.partial} published=${outcome.published}",
    )
    // Gradle считает любой ненулевой код ошибкой сборки и не отдаёт 2 наружу.
    // Частичный выпуск уже записан и должен быть опубликован; фатальная ошибка остаётся 1.
    exitProcess(if (outcome.exitCode == 2) 0 else outcome.exitCode)
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val out = linkedMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i].removePrefix("--")
        val next = args.getOrNull(i + 1)
        if (next == null || next.startsWith("--")) {
            out[key] = "true"
            i += 1
        } else {
            out[key] = next
            i += 2
        }
    }
    return out
}

private fun exitWith(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
