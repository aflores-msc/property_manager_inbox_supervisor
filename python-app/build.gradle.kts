// This module has no JVM sources. Gradle is used purely as a task runner so
// both the Python and Java modules can be driven from one build (`./gradlew`)
// without teaching this module Java's build model.

val isWindows = System.getProperty("os.name").lowercase().contains("win")
val venvDir = layout.projectDirectory.dir(".venv")
val venvPython = venvDir.file(if (isWindows) "Scripts/python.exe" else "bin/python").asFile

tasks.register<Exec>("venv") {
    group = "python"
    description = "Create the virtual environment (.venv) if it doesn't already exist."
    onlyIf { !venvPython.exists() }
    commandLine("python3", "-m", "venv", venvDir.asFile.absolutePath)
}

tasks.register<Exec>("installDependencies") {
    group = "python"
    description = "Install Python dependencies from requirements.txt into the virtual environment."
    dependsOn("venv")
    workingDir = projectDir
    commandLine(venvPython.absolutePath, "-m", "pip", "install", "-q", "-r", "requirements.txt")
}

tasks.register<Exec>("run") {
    group = "application"
    description = "Launch the Estate Beacon desktop application."
    dependsOn("installDependencies")
    workingDir = projectDir
    commandLine(venvPython.absolutePath, "main.py")
}

tasks.register<Exec>("packageApp") {
    group = "build"
    description = "Build a native bundle with PyInstaller (macOS/Linux; see build.bat for Windows)."
    dependsOn("installDependencies")
    workingDir = projectDir
    commandLine("bash", "build_mac.sh")
}

tasks.register<Delete>("clean") {
    group = "build"
    description = "Remove PyInstaller build output."
    delete("build", "dist")
}
