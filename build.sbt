lazy val alpakka = project
  .in(file("."))
  .enablePlugins(PublishUnidoc)
  .aggregate(amqp,
             awslambda,
             cassandra,
             csv,
             dynamodb,
             files,
             ftp,
             googleCloudPubSub,
             hbase,
             ironmq,
             jms,
             mqtt,
             s3,
             simpleCodecs,
             sns,
             sqs,
             sse)

lazy val amqp = project
  .enablePlugins(AutomateHeaderPlugin)
  .disablePlugins(BintrayPlugin)
  .settings(
    name := "akka-stream-alpakka-amqp",
    version := "0.5.3-sigfig",
    publishTo := Some("SigFig Third Party" at "https://artifactory.sigfig.us/artifactory/thirdparty-release-local"),
    Dependencies.Amqp
  )

lazy val awslambda = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-awslambda",
    Dependencies.AwsLambda
  )

lazy val cassandra = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-cassandra",
    Dependencies.Cassandra
  )

lazy val csv = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-csv",
    Dependencies.Csv
  )

lazy val dynamodb = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-dynamodb",
    Dependencies.DynamoDB
  )

lazy val files = project // The name file is taken by `sbt.file`!
  .in(file("file"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-file",
    Dependencies.File
  )

lazy val ftp = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-ftp",
    Dependencies.Ftp,
    parallelExecution in Test := false
  )

lazy val googleCloudPubSub = project
  .in(file("google-cloud-pub-sub"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-google-cloud-pub-sub",
    Dependencies.GooglePubSub
  )

lazy val hbase = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-hbase",
    Dependencies.HBase,
    fork in Test := true
  )

lazy val ironmq = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-ironmq",
    Dependencies.IronMq
  )

lazy val jms = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-jms",
    Dependencies.Jms,
    parallelExecution in Test := false
  )

lazy val mqtt = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-mqtt",
    Dependencies.Mqtt
  )

lazy val s3 = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-s3",
    Dependencies.S3
  )

lazy val simpleCodecs = project
  .in(file("simple-codecs"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-simple-codecs"
  )

lazy val sns = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-sns",
    Dependencies.Sns
  )

lazy val sqs = project
  .in(file("sqs"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-sqs",
    Dependencies.Sqs
  )

lazy val sse = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "akka-stream-alpakka-sse",
    Dependencies.Sse
  )

val Local = config("local")
val defaultParadoxSettings: Seq[Setting[_]] = Seq(
  paradoxTheme := Some(builtinParadoxTheme("generic")),
  paradoxProperties ++= Map(
    "version" -> version.value,
    "scalaVersion" -> scalaVersion.value,
    "scalaBinaryVersion" -> scalaBinaryVersion.value,
    "akkaVersion" -> Dependencies.AkkaVersion,
    "akkaHttpVersion" -> Dependencies.AkkaHttpVersion,
    "extref.akka-docs.base_url" -> s"http://doc.akka.io/docs/akka/${Dependencies.AkkaVersion}/%s",
    "extref.akka-http-docs.base_url" -> s"http://doc.akka.io/docs/akka-http/${Dependencies.AkkaHttpVersion}/%s",
    "extref.java-api.base_url" -> "https://docs.oracle.com/javase/8/docs/api/index.html?%s.html",
    "extref.paho-api.base_url" -> "https://www.eclipse.org/paho/files/javadoc/index.html?%s.html",
    "scaladoc.akka.base_url" -> s"http://doc.akka.io/api/akka/${Dependencies.AkkaVersion}",
    "scaladoc.akka.stream.alpakka.base_url" -> s"http://developer.lightbend.com/docs/api/alpakka/${version.value}"
  ),
  sourceDirectory := baseDirectory.value / "src" / "main"
)

lazy val docs = project
  .enablePlugins(ParadoxPlugin, NoPublish)
  .disablePlugins(BintrayPlugin)
  .settings(
    name := "Alpakka",
    inConfig(Compile)(defaultParadoxSettings),
    ParadoxPlugin.paradoxSettings(Local),
    inConfig(Local)(defaultParadoxSettings),
    paradoxProperties in Local ++= Map(
      // point API doc links to locally generated API docs
      "scaladoc.akka.stream.alpakka.base_url" -> rebase(
        (baseDirectory in alpakka).value,
        "../../../../../"
      )((sbtunidoc.Plugin.UnidocKeys.unidoc in alpakka in Compile).value.head).get
    )
  )
