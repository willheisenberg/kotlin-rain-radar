plugins {
    kotlin("jvm")
    application
}

group = "com.example.rainradar"
version = "1.0.0"


dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.12")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.3.12")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // WebP ImageIO Support
    implementation("org.sejda.imageio:webp-imageio:0.1.6")
}

application {
    mainClass.set("com.example.rainradar.server.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}
