/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.alpakka.amqp.javadsl

import akka.NotUsed
import akka.stream.alpakka.amqp._
import akka.stream.javadsl.Source
import akka.util.ByteString

object AmqpSource {
  import AckPolicy._
  /**
   * Java API: Creates an [[AmqpSource]] with given settings and buffer size.
   */
  def create(settings: AmqpSourceSettings, bufferSize: Int): Source[AckedIncomingMessage[ByteString], NotUsed] =
    Source.fromGraph(new AmqpSourceStage[AckPolicy.Immediate.type, AckedIncomingMessage](AckPolicy.Immediate)(settings, bufferSize))

  /**
   * Java API: Creates an [[AmqpSource]] with given settings and buffer size. You must manually ack the messages
   * with the given actor
   */
  def createWithoutAck(settings: AmqpSourceSettings,
                       bufferSize: Int): Source[UnackedIncomingMessage[ByteString], NotUsed] =
    Source.fromGraph(new AmqpSourceStage[AckPolicy.Manual.type, UnackedIncomingMessage](AckPolicy.Manual)(settings, bufferSize))

}
