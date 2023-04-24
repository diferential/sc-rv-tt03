ThisBuild / version := "1.0"
ThisBuild / scalaVersion := "2.12.16"
ThisBuild / organization := "org.diferential"

val spinalVersion = "1.8.1"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
// val scalatest = ("org.scalatest" %% "scalatest" % "3.2.15" % "test")
// val scalatest = ("org.scalatest" %% "scalatest" % "3.2.15" % "main" )

lazy val scriv = (project in file("."))
  .settings(
    Compile / scalaSource := baseDirectory.value / "hw" / "spinal",
    libraryDependencies ++= Seq(
      spinalCore, spinalLib, spinalIdslPlugin,
      // scalatest,
    )
  )

fork := true
