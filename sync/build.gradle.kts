plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.metro)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "xyz.altaf.kioku.sync"

    sqldelight {
        databases {
            create("SyncDatabase") {
                packageName.set("xyz.altaf.kioku.sync.db")
                dialect(libs.sqldelight.sqliteDialect338)
                generateAsync.set(true)
            }
        }
    }
}

// The protobuf sources are the only definition of the wire format: the Java
// stubs are generated here, never checked in.
protobuf {
    protoc {
        // The compiler and the runtime ship together, so they are pinned to the
        // same version.
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.javalite.get()}"
    }

    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    // Consumers read the protocol messages and the bookkeeping database
    // directly, so both are part of this module's API.
    api(libs.protobuf.javalite)
    api(libs.bundles.sqldelight)

    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.metro.runtime)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
