plugins {
    id("dev.kikugie.loom-back-compat")
}

version = sc.properties.get<String>("mod.version")
group = sc.properties.get<String>("mod.group")

val requiredJava = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21

base {
    archivesName = "Bazzar_v${sc.properties.get<String>("mod.version")}_${sc.current.version}"
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")
    mods {
        register("bazzar") {
            sourceSet(sourceSets.main.get())
        }
    }
    runConfigs.all {
        runDirectory = rootProject.file("run")
    }
}

fabricApi {
    configureDataGeneration {
        client = true
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${sc.properties.get<String>("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties.get<String>("deps.fabric_api")}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        val props = mapOf(
            "version" to sc.properties.get<String>("mod.version"),
            "minecraft_version" to sc.current.version,
            "minecraft_version_range" to sc.properties.get<String>("mod.mc_compat"),
            "loader_version" to sc.properties.get<String>("deps.fabric_loader"),
        )
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") { expand(props) }
    }

    withType<AbstractArchiveTask>().configureEach {
        archiveVersion = ""
    }

    withType<Jar>().configureEach {
        from(rootProject.file("LICENSE.txt")) { rename { "LICENSE_Bazzar" } }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the mod jar and copies it to build/libs/"
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs"))
    }
}
