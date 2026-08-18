import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
    kotlin("jvm") version "2.4.0"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("de.eldoria.plugin-yml.bukkit") version "0.9.0"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "io.pfaumc.pfauprotect"
version = "1.0.0"

// Canvas publishes the dev bundle to maven and the matching server jar to Jenkins under the same
// CI number.
val canvasBuild = "923"
val canvasMinecraftVersion = "26.2"
val rocksdbVersion = "10.4.2"

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

val canvasServer: Configuration by configurations.creating

dependencies {
    paperweight.foliaDevBundle(
        version = "$canvasMinecraftVersion.build.$canvasBuild-stable",
        group = "io.canvasmc.canvas",
    )
    // Everything declared with library(...) is written into plugin.yml's `libraries` block by
    // plugin-yml and downloaded from Maven Central by the server at load time.
    library("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")

    compileOnly("org.rocksdb:rocksdbjni:$rocksdbVersion")

    // library(...) only reaches the main compile classpath, so the test source set needs its own
    // stdlib as long as the default stdlib dependency stays disabled.
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    testImplementation("org.rocksdb:rocksdbjni:$rocksdbVersion:osx")
    testImplementation("org.rocksdb:rocksdbjni:$rocksdbVersion:linux64")
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
        register("pfauprotect.lookup") {
            description = "Read the item ledger for a block"
            default = BukkitPluginDescription.Permission.Default.OP
        }
        register("pfauprotect.inspect") {
            description = "Toggle the inspector and read the ledger by clicking blocks"
            default = BukkitPluginDescription.Permission.Default.OP
        }
        register("pfauprotect.verify") {
            description = "Run the self-checks now instead of waiting for their schedules"
            default = BukkitPluginDescription.Permission.Default.OP
        }
        register("pfauprotect.reconcile") {
            description = "Compare what a player is carrying against the ledger"
            default = BukkitPluginDescription.Permission.Default.OP
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.runServer {
    // Only picks the -add-plugin/--nogui code paths; the jar itself comes from serverJar below.
    version(canvasMinecraftVersion)
    serverJar(layout.file(provider { canvasServer.singleFile }))
}
