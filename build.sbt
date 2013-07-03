import AssemblyKeys._

name := "SQLTap"

organization := "com.paulasmuth"

version := "0.6.2"

mainClass in (Compile, run) := Some("com.paulasmuth.sqltap.SQLTap")

scalaSource in Compile <<= baseDirectory(_ / "src")

scalaVersion := "2.9.1"

assemblySettings

jarName in assembly <<= (version) { v => "sqltap_" + v + ".jar" }

fork in run := true
