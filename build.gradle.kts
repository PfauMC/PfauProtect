import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
    kotlin("jvm") version "2.4.0"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("de.eldoria.plugin-yml.bukkit") version "0.9.0"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "io.pfaumc.pfauprotect"
version = "1.3.0"

// Canvas publishes the dev bundle to maven and the matching server jar to Jenkins under the same
// CI number.
val canvasBuild = "956"
val canvasMinecraftVersion = "26.3"
// 26.3 has no stable builds yet; the dev bundle's version carries the channel.
val canvasChannel = "alpha"
val rocksdbVersion = "10.4.2"
val prometheusVersion = "1.9.0"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.canvasmc.io/releases")
    ivy("https://jenkins.canvasmc.io/job/Canvas") {
        patternLayout { artifact("[revision]/artifact/canvas-server/build/libs/canvas-build.[revision].jar") }
        metadataSources { artifact() }
        content { includeModule("io.canvasmc", "canvas-server") }
    }
}

val canvasServer = configurations.create("canvasServer")

dependencies {
    paperweight.foliaDevBundle(
        version = "$canvasMinecraftVersion.build.$canvasBuild-$canvasChannel",
        group = "io.canvasmc.canvas",
    )
    library("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    library("io.prometheus:prometheus-metrics-core:$prometheusVersion")
    library("io.prometheus:prometheus-metrics-exporter-httpserver:$prometheusVersion")

    compileOnly("org.rocksdb:rocksdbjni:$rocksdbVersion")

    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    testImplementation("io.prometheus:prometheus-metrics-core:$prometheusVersion")
    testImplementation("io.prometheus:prometheus-metrics-exporter-httpserver:$prometheusVersion")
    testImplementation("org.rocksdb:rocksdbjni:$rocksdbVersion:osx")
    testImplementation("org.rocksdb:rocksdbjni:$rocksdbVersion:linux64")
    // Tests also run on Windows dev machines; the server itself never does, so plugin.yml stays
    // without it.
    testImplementation("org.rocksdb:rocksdbjni:$rocksdbVersion:win64")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    canvasServer("io.canvasmc:canvas-server:$canvasBuild@jar")
}

bukkit {
    name = "PfauProtect"
    main = "io.pfaumc.pfauprotect.PfauProtectPlugin"
    apiVersion = canvasMinecraftVersion
    // Canvas is a Folia fork and refuses to enable plugins that don't claim region-thread safety.
    foliaSupported = true
    author = "pfaumc"
    // RocksDB natives only ship in classifier artifacts, and a Gradle dependency notation cannot
    // carry a classifier through plugin-yml: it renders resolved deps as group:name:version.
    // These strings reach the server's Aether resolver verbatim, which accepts the extension and
    // classifier fields. Both platforms are listed so one plugin.yml serves macOS and Linux.
    libraries = listOf(
        "org.rocksdb:rocksdbjni:jar:osx:$rocksdbVersion",
        "org.rocksdb:rocksdbjni:jar:linux64:$rocksdbVersion",
    )
    // The command itself is registered through Brigadier at runtime, so plugin.yml only has to
    // declare the permission that gates it.
    permissions {
        mapOf(
            "lookup" to "Look up block, item, entity and chat history",
            "inspect" to "Toggle the inspector: click a block or entity to see its history",
            "verify" to "Run the self-checks now instead of waiting for their schedules",
            "reconcile" to "Compare what a player is carrying against the ledger",
            "purge" to "Delete history older than an age, keeping what every balance needs",
            "status" to "See how big the bases are and what waits to be written",
            "teleport" to "Teleport to where a lookup line happened by clicking its mark",
            "rollback" to "Preview and apply rollbacks of what the ledger recorded",
        ).forEach { (name, text) ->
            register("pfauprotect.$name") {
                description = text
                default = BukkitPluginDescription.Permission.Default.OP
            }
        }
        // Held by no one by default, and it outranks pfauprotect.teleport: a holder of both is still limited.
        register("pfauprotect.teleport.spectator") {
            description = "Teleport by a lookup line's mark only while in spectator mode, even with pfauprotect.teleport"
            default = BukkitPluginDescription.Permission.Default.FALSE
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // Booting the server registries writes config/ and logs/ into the working directory.
    val work = layout.buildDirectory.dir("test-work")
    workingDir(work)
    doFirst { work.get().asFile.mkdirs() }
    systemProperty("pfauprotect.sources", layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath)
}

// Reads a copy of the ledger offline; scripts/ledger-rows.sh takes the copy.
tasks.register<JavaExec>("ledgerRows") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.pfaumc.pfauprotect.tools.LedgerRowsKt")
    workingDir = rootDir
}

// Fills a base with history and times the lookups on it, see tools/Bench.kt:
// ./gradlew bench --args="gen D:/pp-bench 1000000000", then --args="run D:/pp-bench".
tasks.register<JavaExec>("bench") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.pfaumc.pfauprotect.tools.BenchKt")
    val work = layout.buildDirectory.dir("bench-work")
    workingDir(work)
    doFirst { work.get().asFile.mkdirs() }
    maxHeapSize = "4g"
}

tasks.runServer {
    // Only picks the -add-plugin/--nogui code paths; the jar itself comes from serverJar below.
    version(canvasMinecraftVersion)
    serverJar(layout.file(provider { canvasServer.singleFile }))
}
