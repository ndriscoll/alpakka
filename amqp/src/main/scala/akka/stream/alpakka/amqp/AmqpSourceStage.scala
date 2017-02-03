/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.alpakka.amqp

import akka.actor.ActorRef
import akka.stream._
import akka.stream.stage.GraphStageLogic.StageActor
import akka.stream.stage._
import akka.util.{ByteString, Timeout}
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client._
import scala.concurrent.duration._

import scala.collection.mutable
import scala.concurrent.Future

final case class IncomingMessage[A](bytes: A, envelope: Envelope, properties: BasicProperties)

object IncomingMessageWithAck {
  final case class Ack(deliveryTag: Long) extends Command(c => c.basicAck(deliveryTag, false))
  final case class Nack(deliveryTag: Long, requeue: Boolean)
      extends Command(c => c.basicNack(deliveryTag, false, requeue))

  class Command[A](f: (Channel) => A) extends ((Channel) => A) {
    override def apply(c: Channel): A = f(c)
  }

}

final case class IncomingMessageWithAck[A](bytes: A, envelope: Envelope, properties: BasicProperties, ref: ActorRef)(
    implicit private[amqp] val timeout: Timeout = 5.seconds
) {
  import IncomingMessageWithAck._
  import akka.pattern.ask

  /**
   * Ack this message with the channel that delivered it
   */
  def ack(): Unit = ref ! Ack(envelope.getDeliveryTag)

  def nack(requeue: Boolean): Unit = ref ! Nack(envelope.getDeliveryTag, requeue)

}

object AmqpSourceStage {

  private val defaultAttributes = Attributes.name("AmqpSource")

}

/**
 * Connects to an AMQP server upon materialization and consumes messages from it emitting them
 * into the stream. Each materialized source will create one connection to the broker.
 * As soon as an `IncomingMessage` is sent downstream, an ack for it is sent to the broker.
 *
 * @param bufferSize The max number of elements to prefetch and buffer at any given time.
 */
final class AmqpSourceStage(settings: AmqpSourceSettings, bufferSize: Int)
    extends GraphStage[SourceShape[IncomingMessage[ByteString]]]
    with AmqpConnector { stage =>

  val out = Outlet[IncomingMessage[ByteString]]("AmqpSource.out")

  override val shape: SourceShape[IncomingMessage[ByteString]] = SourceShape.of(out)

  override protected def initialAttributes: Attributes = AmqpSourceStage.defaultAttributes

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with AmqpConnectorLogic {

      override val settings = stage.settings
      override def connectionFactoryFrom(settings: AmqpConnectionSettings) = stage.connectionFactoryFrom(settings)
      override def newConnection(factory: ConnectionFactory, settings: AmqpConnectionSettings) =
        stage.newConnection(factory, settings)

      private val queue = mutable.Queue[IncomingMessage[ByteString]]()

      override def whenConnected(): Unit = {
        import scala.collection.JavaConverters._
        // we have only one consumer per connection so global is ok
        channel.basicQos(bufferSize, true)
        val consumerCallback = getAsyncCallback(handleDelivery)
        val shutdownCallback = getAsyncCallback[Option[ShutdownSignalException]] {
          case Some(ex) => failStage(ex)
          case None => completeStage()
        }

        val amqpSourceConsumer = new DefaultConsumer(channel) {
          override def handleDelivery(consumerTag: String,
                                      envelope: Envelope,
                                      properties: BasicProperties,
                                      body: Array[Byte]): Unit =
            consumerCallback.invoke(IncomingMessage(ByteString(body), envelope, properties))

          override def handleCancel(consumerTag: String): Unit =
            // non consumer initiated cancel, for example happens when the queue has been deleted.
            shutdownCallback.invoke(None)

          override def handleShutdownSignal(consumerTag: String, sig: ShutdownSignalException): Unit =
            // "Called when either the channel or the underlying connection has been shut down."
            shutdownCallback.invoke(Option(sig))
        }

        def setupNamedQueue(settings: NamedQueueSourceSettings): Unit =
          channel.basicConsume(
            settings.queue,
            false, // never auto-ack
            settings.consumerTag, // consumer tag
            settings.noLocal,
            settings.exclusive,
            settings.arguments.asJava,
            amqpSourceConsumer
          )

        def setupTemporaryQueue(settings: TemporaryQueueSourceSettings): Unit = {
          // this is a weird case that required dynamic declaration, the queue name is not known
          // up front, it is only useful for sources, so that's why it's not placed in the AmqpConnectorLogic
          val queueName = channel.queueDeclare().getQueue
          channel.queueBind(queueName, settings.exchange, settings.routingKey.getOrElse(""))
          channel.basicConsume(
            queueName,
            amqpSourceConsumer
          )
        }

        settings match {
          case settings: NamedQueueSourceSettings => setupNamedQueue(settings)
          case settings: TemporaryQueueSourceSettings => setupTemporaryQueue(settings)
        }

      }

      def handleDelivery(message: IncomingMessage[ByteString]): Unit =
        if (isAvailable(out)) {
          pushAndAckMessage(message)
        } else {
          if (queue.size + 1 > bufferSize) {
            failStage(new RuntimeException(s"Reached maximum buffer size $bufferSize"))
          } else {
            queue.enqueue(message)
          }
        }

      setHandler(out, new OutHandler {
        override def onPull(): Unit =
          if (queue.nonEmpty) {
            pushAndAckMessage(queue.dequeue())
          }

      })

      def pushAndAckMessage(message: IncomingMessage[ByteString]): Unit = {
        push(out, message)
        // ack it as soon as we have passed it downstream
        // TODO ack less often and do batch acks with multiple = true would probably be more performant
        channel.basicAck(
          message.envelope.getDeliveryTag,
          false // just this single message
        )
      }

    }

}

object AmqpSourceWithoutAckStage {

  private val defaultAttributes = Attributes.name("AmqpSourceWithoutAck")

}

/**
 * Connects to an AMQP server upon materialization and consumes messages from it emitting them
 * into the stream. Each materialized source will create one connection to the broker.
 * As soon as an `IncomingMessage` is sent downstream, an ack for it is sent to the broker.
 *
 * @param bufferSize The max number of elements to prefetch and buffer at any given time.
 */
final class AmqpSourceWithoutAckStage(settings: AmqpSourceSettings, bufferSize: Int)
    extends GraphStage[SourceShape[IncomingMessageWithAck[ByteString]]]
    with AmqpConnector { stage =>

  val out = Outlet[IncomingMessageWithAck[ByteString]]("AmqpSourceWithoutAck.out")

  override val shape: SourceShape[IncomingMessageWithAck[ByteString]] = SourceShape.of(out)

  override protected def initialAttributes: Attributes = AmqpSourceWithoutAckStage.defaultAttributes

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with AmqpConnectorLogic {

      override val settings = stage.settings
      override def connectionFactoryFrom(settings: AmqpConnectionSettings) = stage.connectionFactoryFrom(settings)
      override def newConnection(factory: ConnectionFactory, settings: AmqpConnectionSettings) =
        stage.newConnection(factory, settings)

      private val queue = mutable.Queue[IncomingMessageWithAck[ByteString]]()

      override def whenConnected(): Unit = {

        import IncomingMessageWithAck._
        val ref = getStageActor {
          case (actor, msg: Command[_]) =>
            msg(channel)

        }.ref
        import scala.collection.JavaConverters._
        // we have only one consumer per connection so global is ok
        channel.basicQos(bufferSize, true)
        val consumerCallback = getAsyncCallback(handleDelivery)
        val shutdownCallback = getAsyncCallback[Option[ShutdownSignalException]] {
          case Some(ex) => failStage(ex)
          case None => completeStage()
        }

        val amqpSourceConsumer = new DefaultConsumer(channel) {
          override def handleDelivery(consumerTag: String,
                                      envelope: Envelope,
                                      properties: BasicProperties,
                                      body: Array[Byte]): Unit =
            consumerCallback.invoke(IncomingMessageWithAck(ByteString(body), envelope, properties, ref))

          override def handleCancel(consumerTag: String): Unit =
            // non consumer initiated cancel, for example happens when the queue has been deleted.
            shutdownCallback.invoke(None)

          override def handleShutdownSignal(consumerTag: String, sig: ShutdownSignalException): Unit =
            // "Called when either the channel or the underlying connection has been shut down."
            shutdownCallback.invoke(Option(sig))
        }

        def setupNamedQueue(settings: NamedQueueSourceSettings): Unit =
          channel.basicConsume(
            settings.queue,
            false, // never auto-ack
            settings.consumerTag, // consumer tag
            settings.noLocal,
            settings.exclusive,
            settings.arguments.asJava,
            amqpSourceConsumer
          )

        def setupTemporaryQueue(settings: TemporaryQueueSourceSettings): Unit = {
          // this is a weird case that required dynamic declaration, the queue name is not known
          // up front, it is only useful for sources, so that's why it's not placed in the AmqpConnectorLogic
          val queueName = channel.queueDeclare().getQueue
          channel.queueBind(queueName, settings.exchange, settings.routingKey.getOrElse(""))
          channel.basicConsume(
            queueName,
            amqpSourceConsumer
          )
        }

        settings match {
          case settings: NamedQueueSourceSettings => setupNamedQueue(settings)
          case settings: TemporaryQueueSourceSettings => setupTemporaryQueue(settings)
        }

      }

      def handleDelivery(message: IncomingMessageWithAck[ByteString]): Unit =
        if (isAvailable(out)) {
          push(out, message)
        } else {
          if (queue.size + 1 > bufferSize) {
            failStage(new RuntimeException(s"Reached maximum buffer size $bufferSize"))
          } else {
            queue.enqueue(message)
          }
        }

      setHandler(out, new OutHandler {
        override def onPull(): Unit =
          if (queue.nonEmpty) {
            push(out, queue.dequeue())
          }

      })

    }

}
