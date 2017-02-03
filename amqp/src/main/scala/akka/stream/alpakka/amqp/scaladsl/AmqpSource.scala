/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.alpakka.amqp.scaladsl

import akka.NotUsed
import akka.stream.alpakka.amqp._
import akka.stream.scaladsl.Source
import akka.util.ByteString

object AmqpSource {

  /**
   * Scala API: Creates an [[AmqpSource]] with given settings and buffer size.
   */
  def apply(settings: AmqpSourceSettings, bufferSize: Int): Source[IncomingMessage[ByteString], NotUsed] =
    Source.fromGraph(new AmqpSourceStage(settings, bufferSize))

  /**
   * Scala API: Creates an [[AmqpSource]] with given settings and buffer size. You must manually ack the messages
   * with the given actor
   */
  def withoutAutoAck(settings: AmqpSourceSettings,
                     bufferSize: Int): Source[IncomingMessageWithAck[ByteString], NotUsed] =
    Source.fromGraph(new AmqpSourceWithoutAckStage(settings, bufferSize))

}
