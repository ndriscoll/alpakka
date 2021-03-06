/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.alpakka.s3.scaladsl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.s3.auth.AWSCredentials
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

trait S3ClientIntegrationSpec
    extends FlatSpecLike
    with BeforeAndAfterAll
    with Matchers
    with ScalaFutures
    with IntegrationPatience {

  implicit val system: ActorSystem
  implicit val materializer = ActorMaterializer()

  //#client
  val awsCredentials = AWSCredentials(accessKeyId = "my-AWS-access-key-ID", secretAccessKey = "my-AWS-password")
  val s3Client = new S3Client(credentials = awsCredentials, region = "us-east-1")(system, materializer)
  //#client

}
