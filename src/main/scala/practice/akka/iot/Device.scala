package practice.akka.iot

import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior, PostStop, Signal }
import practice.akka.iot.Device.DeviceCommand

object Device {

  def apply(groupId: String, deviceId: String): Behavior[DeviceCommand] =
    Behaviors.setup(context => new Device(context, groupId, deviceId))

  sealed trait DeviceCommand

  final case class ReadTemperature(requestId: Long, replyTo: ActorRef[RespondTemperature]) extends DeviceCommand
  final case class RespondTemperature(requestId: Long, deviceId: String, value: Option[Double])

  final case class RecordTemperature(requestId: Long, value: Double, replyTo: ActorRef[TemperatureRecorded])
      extends DeviceCommand
  final case class TemperatureRecorded(requestId: Long)

  case object Passivate extends DeviceCommand
}

class Device(context: ActorContext[DeviceCommand], groupId: String, deviceId: String)
    extends AbstractBehavior[DeviceCommand](context) {

  import practice.akka.iot.Device._

  var lastTemperatureReading: Option[Double] = None

  context.log.info(s"Device actor $groupId-$deviceId started")

  override def onMessage(msg: DeviceCommand): Behavior[DeviceCommand] = {
    msg match {
      case RecordTemperature(requestId, value, replyTo) => {
        context.log.info(s"Recorded temperature reading $value with $requestId")
        lastTemperatureReading = Some(value)
        replyTo ! TemperatureRecorded(requestId)
        this
      }
      case ReadTemperature(requestId, replyTo) => {
        replyTo ! RespondTemperature(requestId, deviceId, lastTemperatureReading)
        this
      }
      case Passivate =>
        Behaviors.stopped
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[DeviceCommand]] = {
    case PostStop =>
      context.log.info(s"Device actor $groupId-$deviceId started")
      this
  }
}
