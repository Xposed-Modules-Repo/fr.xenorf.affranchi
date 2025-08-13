tasks.register<Delete>("cleanAll") {
    delete(rootProject.layout.buildDirectory)
}
