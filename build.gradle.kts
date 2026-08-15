plugins {
    kotlin("jvm") version "2.4.0"
    id("de.eldoria.plugin-yml.bukkit") version "0.9.0"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "io.pfaumc.pfauprotect"
version = "1.0.0"

// Canvas publishes the API to maven and the matching server jar to Jenkins under the same CI number.
val canvasBuild = "923"
val canvasMinecraftVersion = "26.2"

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
    compileOnly("io.canvasmc.canvas:canvas-api:$canvasMinecraftVersion.build.$canvasBuild-stable")
    // Everything declared with library(...) is written into plugin.yml's `libraries` block by
    // plugin-yml and downloaded from Maven Central by the server at load time.
    library("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    canvasServer("io.canvasmc:canvas-server:$canvasBuild@jar")
}

bukkit {
    name = "PfauProtect"
    main = "io.pfaumc.pfauprotect.PfauProtectPlugin"
    apiVersion = canvasMinecraftVersion
    // Canvas is a Folia fork and refuses to enable plugins that don't claim region-thread safety.
    foliaSupported = true
    author = "pfaumc"
}

tasks.runServer {
    // Only picks the -add-plugin/--nogui code paths; the jar itself comes from serverJar below.
    version(canvasMinecraftVersion)
    serverJar(layout.file(provider { canvasServer.singleFile }))
}
