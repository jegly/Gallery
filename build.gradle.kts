// Build-tool transitives (AGP / Kotlin / Hilt plugin classpath) that Dependabot flags. They never
// ship in the APK; constraints lift them to patched releases.
buildscript {
    val nettyVersion = "4.1.139.Final"
    val bouncyCastleVersion = "1.86"
    dependencies {
        constraints {
            classpath("io.netty:netty-buffer:$nettyVersion")
            classpath("io.netty:netty-codec:$nettyVersion")
            classpath("io.netty:netty-codec-http:$nettyVersion")
            classpath("io.netty:netty-codec-http2:$nettyVersion")
            classpath("io.netty:netty-common:$nettyVersion")
            classpath("io.netty:netty-handler:$nettyVersion")
            classpath("io.netty:netty-handler-proxy:$nettyVersion")
            classpath("io.netty:netty-resolver:$nettyVersion")
            classpath("io.netty:netty-transport:$nettyVersion")
            classpath("io.netty:netty-transport-native-epoll:$nettyVersion")
            classpath("org.bouncycastle:bcprov-jdk18on:$bouncyCastleVersion")
            classpath("org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
