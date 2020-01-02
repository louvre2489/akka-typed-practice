package practice.akka.iot

import akka.actor.typed.{ActorRef, Behavior, PostStop, Signal}
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import practice.akka.iot.Device.DeviceCommand
import practice.akka.iot.DeviceGroup.DeviceGroupCommand
import practice.akka.iot.DeviceGroupQuery.DeviceGroupQueryCommand
import practice.akka.iot.DeviceManager.DeviceManagerCommand

object DeviceManager {

  def apply(): Behavior[DeviceManagerCommand] =
    Behaviors.setup(context => new DeviceManager(context))

  sealed trait DeviceManagerCommand

  final case class RequestTrackDevice(groupId: String, deviceId: String, replyTo: ActorRef[DeviceRegistered])
      extends DeviceManagerCommand
      with DeviceGroupCommand

  final case class DeviceRegistered(device: ActorRef[DeviceCommand])

  final case class RequestDeviceList(requestId: Long, groupId: String, replyTo: ActorRef[ReplyDeviceList])
      extends DeviceManagerCommand
      with DeviceGroupCommand

  final case class ReplyDeviceList(requestId: Long, ids: Set[String])

  private final case class DeviceGroupTerminated(groupId: String) extends DeviceManagerCommand

  final case class RequestAllTemperatures(requestId: Long, groupId: String, replyTo: ActorRef[RespondAllTemperatures]) extends DeviceGroupQueryCommand with DeviceGroupCommand with DeviceManagerCommand

  final case class RespondAllTemperatures(requestId: Long, temperatures: Map[String, TemperatureReading])

  sealed trait TemperatureReading
  final case class Temperature(value: Double) extends TemperatureReading
  case object TemperatureNotAvailable extends TemperatureReading
  case object DeviceNotAvailable extends TemperatureReading
  case object DeviceTimedOut extends TemperatureReading
}

class DeviceManager(context: ActorContext[DeviceManagerCommand])
    extends AbstractBehavior[DeviceManagerCommand](context) {

  import DeviceManager._

  var groupIdToActor = Map.empty[String, ActorRef[DeviceGroupCommand]]

  context.log.info("DeviceManager started")

  override def onMessage(msg: DeviceManagerCommand): Behavior[DeviceManagerCommand] =
    msg match {
      case trackMsg @ RequestTrackDevice(groupId, _, replyTo) =>
        groupIdToActor.get(groupId) match {
          case Some(ref) =>
            ref ! trackMsg
          case None =>
            context.log.info(s"Creating device group actor for $groupId")
            val groupActor = context.spawn(DeviceGroup(groupId), "group-" + groupId)
            context.watchWith(groupActor, DeviceGroupTerminated(groupId))
            groupActor ! trackMsg
            groupIdToActor += groupId -> groupActor
        }
        this
      case req @ RequestDeviceList(requestId, groupId, replyTo) =>
        groupIdToActor.get(groupId) match {
          case Some(ref) =>
            ref ! req
          case None =>
            replyTo ! ReplyDeviceList(requestId, Set.empty)
        }
        this
      case DeviceGroupTerminated(groupId) =>
        context.log.info(s"Device group actor for $groupId has been terminated")
        groupIdToActor -= groupId
        this
    }

  override def onSignal: PartialFunction[Signal, Behavior[DeviceManagerCommand]] = {
    case PostStop =>
      context.log.info("DeviceManager stopped")
      this
  }
}
