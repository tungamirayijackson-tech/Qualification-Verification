// Root build. The JVM work lives in :backend; the Angular console is built by npm.
// A shared convention plugin (manuscript S03) arrives when a second JVM module does --
// with one subproject it would be indirection without payoff.
plugins {
    base
}

allprojects {
    group = "zw.ac.qvs"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
