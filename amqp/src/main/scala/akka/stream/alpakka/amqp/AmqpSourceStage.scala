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

sealed trait IncomingMessage[A] {
  def bytes: A
  def envelope: Envelope
  def properties: BasicProperties
}

final case class AckedIncomingMessage[A](bytes: A, envelope: Envelope, properties: BasicProperties) extends IncomingMessage[A]

object UnackedIncomingMessage {
  final case class Ack(deliveryTag: Long) extends Command(c => c.basicAck(deliveryTag, false))
  final case class Nack(deliveryTag: Long, requeue: Boolean)
      extends Command(c => c.basicNack(deliveryTag, false, requeue))

  class Command[A](f: (Channel) => A) extends ((Channel) => A) {
    override def apply(c: Channel): A = f(c)
  }

}

final case class UnackedIncomingMessage[A](bytes: A, envelope: Envelope, properties: BasicProperties, ref: ActorRef)(
    implicit private[amqp] val timeout: Timeout = 5.seconds
) extends IncomingMessage[A] {
  import UnackedIncomingMessage._
  import akka.pattern.ask

  /**
   * Ack this message with the channel that delivered it
   */
  def ack(): Unit = ref ! Ack(envelope.getDeliveryTag)
  private[this] def acked: AckedIncomingMessage[A] = AckedIncomingMessage(bytes, envelope, properties)
  def withAck[B](f: AckedIncomingMessage[A] => B): B = {
    val ret = f(acked)
    ack()
    ret
  }

  def nack(requeue: Boolean): Unit = ref ! Nack(envelope.getDeliveryTag, requeue)

}

sealed trait AckPolicy
object AckPolicy {
  case object Immediate extends AckPolicy
  case object Manual extends AckPolicy
}

sealed trait AckPolicyOps[P<:AckPolicy] {
  import AckPolicyOps._
  type T[A] <: IncomingMessage[A]
    def withPushPolicy[A](msg: UnackedIncomingMessage[A]): (T[A] => Unit) => Unit
}

object AckPolicyOps {
  type Aux[P<: AckPolicy,M[A]] = AckPolicyOps[P] {type T[A] = M[A]}
  implicit object Immediate extends AckPolicyOps[AckPolicy.Immediate.type] {
    type T[A] = AckedIncomingMessage[A]
    override def withPushPolicy[A](msg: UnackedIncomingMessage[A]): ((AckedIncomingMessage[A]) => Unit) => Unit = {
      f => msg.withAck[Unit](f)
    }
  }
  implicit object Manual extends AckPolicyOps[AckPolicy.Manual.type]{
    type T[A] = UnackedIncomingMessage[A]
    override def withPushPolicy[A](msg: UnackedIncomingMessage[A]): ((UnackedIncomingMessage[A]) => Unit) => Unit = {f => f(msg)}
  }
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
final class AmqpSourceStage[P <: AckPolicy, M[_]<:IncomingMessage[_]](p: AckPolicy)(settings: AmqpSourceSettings, bufferSize: Int)
                                                                     (implicit aux: AckPolicyOps.Aux[P,M])
    extends GraphStage[SourceShape[M[ByteString]]]
    with AmqpConnector { stage =>

  val out = Outlet[M[ByteString]]("AmqpSource.out")

  override val shape: SourceShape[M[ByteString]] = SourceShape.of(out)

  override protected def initialAttributes: Attributes = AmqpSourceStage.defaultAttributes

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with AmqpConnectorLogic {

      override val settings = stage.settings
      override def connectionFactoryFrom(settings: AmqpConnectionSettings) = stage.connectionFactoryFrom(settings)
      override def newConnection(factory: ConnectionFactory, settings: AmqpConnectionSettings) =
        stage.newConnection(factory, settings)

      private val queue = mutable.Queue[UnackedIncomingMessage[ByteString]]()

      override def whenConnected(): Unit = {

        import UnackedIncomingMessage._
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
            consumerCallback.invoke(UnackedIncomingMessage(ByteString(body), envelope, properties, ref))

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

      def handleDelivery(message: UnackedIncomingMessage[ByteString]): Unit =
        if (isAvailable(out)) {
          applyAckPolicy(message)
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
            applyAckPolicy(queue.dequeue())
          }

      })

      def applyAckPolicy(message: UnackedIncomingMessage[ByteString]): Unit = {
        aux.withPushPolicy(message){m => push(out, m)}
      }
//      def pushAndAckMessage(message: IncomingMessage[ByteString]): Unit = {
//        push(out, message)
//        // ack it as soon as we have passed it downstream
//        // TODO ack less often and do batch acks with multiple = true would probably be more performant
//        channel.basicAck(
//          message.envelope.getDeliveryTag,
//          false // just this single message
//        )
//      }

    }

}