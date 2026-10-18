plugins {
    `java-library`
}

tasks.withType<JavaCompile> {
    options.release = 11
    options.encoding = "UTF-8"
}

dependencies {
    implementation(project(":third_party:brouter-util"))
    implementation(project(":third_party:brouter-codec"))
    implementation(project(":third_party:brouter-expressions"))
}
