name := "akka-typed-practice"

version := "0.1"

scalaVersion := "2.13.1"

libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % "2.6.1"
libraryDependencies += "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.1" % Test
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8" % Test