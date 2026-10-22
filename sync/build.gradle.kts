plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

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

dependencies {
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.bundles.sqldelight)
    implementation(libs.protobuf.javalite)
    implementation(libs.kotlinx.datetime)
}
