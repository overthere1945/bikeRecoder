plugins {
    `java-library`
}

tasks.withType<JavaCompile> {
    options.release = 11
    options.encoding = "UTF-8"
}
