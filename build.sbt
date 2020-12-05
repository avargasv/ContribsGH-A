name := "ContribsGH-A"

version := "0.1"

organization := "com.avargasv"

scalaVersion := "2.11.1"

resolvers ++= Seq("snapshots"         at "https://oss.sonatype.org/content/repositories/snapshots",
                  "staging"           at "https://oss.sonatype.org/content/repositories/staging",
                  "releases"          at "https://oss.sonatype.org/content/repositories/releases")

enablePlugins(JettyPlugin)

containerPort := 8080

scalacOptions ++= Seq("-deprecation", "-unchecked")

libraryDependencies ++= {
  val liftVersion = "3.3.0"
  val akkaVersion = "2.5.21"
  Seq(
    "net.liftweb"             %% "lift-webkit"            % liftVersion           % "compile",
    "net.liftmodules"         %% "lift-jquery-module_3.3" % "2.10",
    "org.eclipse.jetty"       %  "jetty-webapp"           % "8.1.7.v20120910"     % "container,test",
    "org.eclipse.jetty.orbit" %  "javax.servlet"          % "3.0.0.v201112011016" % "container,test" artifacts Artifact("javax.servlet", "jar", "jar"),
    "ch.qos.logback"          %  "logback-classic"        % "1.0.6"               % "compile",
    "org.scala-lang"          %  "scala-library"          % "2.11.1"              % "compile",
    "com.typesafe.akka"       %% "akka-actor-typed"       % akkaVersion           % "compile",
    "io.spray"                %% "spray-http"             % "1.3.4"               % "compile",
    "io.spray"                %% "spray-httpx"            % "1.3.4"               % "compile",
    "io.spray"                %% "spray-client"           % "1.3.4"               % "compile"
  )
}
