import org.asciidoctor.gradle.jvm.AsciidoctorTask
import org.jetbrains.dokka.gradle.DokkaTask
import java.util.Date

plugins {
    `java-library`
    kotlin("jvm") version "1.4.20"

    // project plugins
    groovy

    // test coverage
    jacoco

    // ide plugin
    idea

    // publish plugin
    `maven-publish`

    // artifact signing - necessary on Maven Central
    signing

    // intershop version plugin
    id("com.intershop.gradle.scmversion") version "6.2.0"

    id("com.github.johnrengelman.shadow") version "6.1.0"

    // plugin for documentation
    id("org.asciidoctor.jvm.convert") version "3.3.0"

    // documentation
    id("org.jetbrains.dokka") version "0.10.1"

    // code analysis for kotlin
    id("io.gitlab.arturbosch.detekt") version "1.15.0"
}

scm {
    version.initialVersion = "1.0.0"
}

group = "com.intershop.gradle.jobrunner"
description = "ICM JobRunner library to use in Gradle Plugins"
version = scm.version.version

val sonatypeUsername: String by project
val sonatypePassword: String? by project

java {
    withSourcesJar()
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// set correct project status
if (project.version.toString().endsWith("-SNAPSHOT")) {
    status = "snapshot'"
}

detekt {
    input = files("src/main/kotlin")
    config = files("detekt.yml")
}

val shaded by configurations.creating
val compileOnly = configurations.getByName("implementation")
compileOnly.extendsFrom(shaded)

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "1.8"
        }
    }

    val relocateShadowJar = register<com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation>("relocateShadowJar") {
        target = shadowJar.get()
        prefix = "intershop.shadow"
    }

    val shadowJar = named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        mergeServiceFiles()
        configurations = listOf(shaded)

        dependsOn(relocateShadowJar)
    }

    named<Jar>("jar") {
        enabled = false
    }

    named<Task>("assemble") {
        dependsOn(shadowJar)
    }

    val copyAsciiDoc = register<Copy>("copyAsciiDoc") {
        includeEmptyDirs = false

        val outputDir = file("$buildDir/tmp/asciidoctorSrc")
        val inputFiles = fileTree(rootDir) {
            include("**/*.asciidoc")
            exclude("build/**")
        }

        inputs.files.plus( inputFiles )
        outputs.dir( outputDir )

        doFirst {
            outputDir.mkdir()
        }

        from(inputFiles)
        into(outputDir)
    }

    withType<AsciidoctorTask> {
        dependsOn(copyAsciiDoc)

        setSourceDir(file("$buildDir/tmp/asciidoctorSrc"))
        sources(delegateClosureOf<PatternSet> {
            include("README.asciidoc")
        })

        outputOptions {
            setBackends(listOf("html5", "docbook"))
        }

        options = mapOf( "doctype" to "article",
            "ruby"    to "erubis")
        attributes = mapOf(
            "latestRevision"        to  project.version,
            "toc"                   to "left",
            "toclevels"             to "2",
            "source-highlighter"    to "coderay",
            "icons"                 to "font",
            "setanchors"            to "true",
            "idprefix"              to "asciidoc",
            "idseparator"           to "-",
            "docinfo1"              to "true")
    }

    getByName("shadowJar").dependsOn("asciidoctor")

    val dokka by existing(DokkaTask::class) {
        outputFormat = "javadoc"
        outputDirectory = "$buildDir/javadoc"

        // Java 8 is only version supported both by Oracle/OpenJDK and Dokka itself
        // https://github.com/Kotlin/dokka/issues/294
        enabled = JavaVersion.current().isJava8
    }

    register<Jar>("sourceJar") {
        description = "Creates a JAR that co" +
                "ntains the source code."

        from(sourceSets.getByName("main").allSource)
        archiveClassifier.set("sources")
    }

    register<Jar>("javaDoc") {
        dependsOn(dokka)
        from(dokka)
        archiveClassifier.set("javadoc")
    }
}

publishing {
    publications {
        create("intershopMvn", MavenPublication::class.java) {
            artifact(tasks.getByName("shadowJar"))
            artifact(tasks.getByName("sourceJar"))
            artifact(tasks.getByName("javaDoc"))

            artifact(File(buildDir, "docs/asciidoc/html5/README.html")) {
                classifier = "reference"
            }

            artifact(File(buildDir, "docs/asciidoc/docbook/README.xml")) {
                classifier = "docbook"
            }

            pom {
                name.set(project.name)
                description.set(project.description)
                url.set("https://github.com/IntershopCommunicationsAG/${project.name}")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                organization {
                    name.set("Intershop Communications AG")
                    url.set("http://intershop.com")
                }
                developers {
                    developer {
                        id.set("m-raab")
                        name.set("M. Raab")
                        email.set("mraab@intershop.de")
                    }
                }
                scm {
                    connection.set("git@github.com:IntershopCommunicationsAG/${project.name}.git")
                    developerConnection.set("git@github.com:IntershopCommunicationsAG/${project.name}.git")
                    url.set("https://github.com/IntershopCommunicationsAG/${project.name}")
                }
            }
        }
    }
    repositories {
        maven {
            val releasesRepoUrl = "https://oss.sonatype.org/service/local/staging/deploy/maven2"
            val snapshotsRepoUrl = "https://oss.sonatype.org/content/repositories/snapshots"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
            credentials {
                username = sonatypeUsername
                password = sonatypePassword
            }
        }
    }
}

dependencies {
    shaded("jakarta.ws.rs:jakarta.ws.rs-api:2.1.6")
    shaded("org.glassfish.jersey.core:jersey-client:2.29.1") {
        exclude(group = "org.glassfish.hk2.external", module="jakarta.inject")
    }
    shaded("org.glassfish.jersey.media:jersey-media-json-jackson:2.29.1")
    shaded("org.codehaus.jettison:jettison:1.4.1")
    shaded("org.glassfish.jersey.inject:jersey-hk2:2.29.1")
    shaded("commons-httpclient:commons-httpclient:3.1")

    compileOnly("org.slf4j:slf4j-api:1.7.30")
}

repositories {
    mavenCentral()
    // necessary for detect
    jcenter()
}
