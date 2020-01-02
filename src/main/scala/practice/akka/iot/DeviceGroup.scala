package practice.akka.iot

import akka.actor.typed.{ ActorRef, Behavior, PostStop, Signal }
import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors }
import practice.akka.iot.Device.DeviceCommand
import practice.akka.iot.DeviceGroup.DeviceGroupCommand
import practice.akka.iot.DeviceManager.RequestAllTemperatures

import scala.concurrent.duration._

object DeviceGroup {

  def apply(groupId: String): Behavior[DeviceGroupCommand] =
    Behaviors.setup(context => new DeviceGroup(context, groupId))

  trait DeviceGroupCommand

  private final case class DeviceTerminated(device: ActorRef[DeviceCommand], groupId: String, deviceId: String)
      extends DeviceGroupCommand
}

class DeviceGroup(context: ActorContext[DeviceGroupCommand], groupId: String)
    extends AbstractBehavior[DeviceGroupCommand](context) {

  import DeviceGroup._
  import DeviceManager.{ DeviceRegistered, ReplyDeviceList, RequestDeviceList, RequestTrackDevice }

  private var deviceIdToActor = Map.empty[String, ActorRef[DeviceCommand]]

  context.log.info(s"DeviceGroup $groupId started")

  override def onMessage(msg: DeviceGroupCommand): Behavior[DeviceGroupCommand] =
    msg match {
      case trackMsg @ RequestTrackDevice(`groupId`, deviceId, replyTo) =>
        deviceIdToActor.get(deviceId) match {
          case Some(deviceActor) =>
            replyTo ! DeviceRegistered(deviceActor)
          case None =>
            context.log.info(s"Creating device actor for ${trackMsg.deviceId}")

            val deviceActor = context.spawn(Device(groupId, deviceId), s"device-$deviceId")
            context.watchWith(deviceActor, DeviceTerminated(deviceActor, groupId, deviceId))
            deviceIdToActor += deviceId -> deviceActor
            replyTo ! DeviceRegistered(deviceActor)
        }
        this

      case RequestTrackDevice(gId, _, _) =>
        context.log.warn(s"Ignoring TrackDevice request for $gId. This actor is responsible for $groupId.")
        this

      case RequestDeviceList(requestId, gId, replyTo) =>
        if (gId == groupId) {
          replyTo ! ReplyDeviceList(requestId, deviceIdToActor.keySet)
          this
        } else
          Behaviors.unhandled

      case RequestAllTemperatures(requestId, gId, replyTo) =>
        if (gId == groupId) {
          context.spawnAnonymous(
            DeviceGroupQuery(deviceIdToActor, requestId, requester = replyTo, 3.seconds)
          )
          this
        } else
          Behaviors.unhandled

      case DeviceTerminated(_, _, deviceId) =>
        context.log.warn(s"Device actor for $deviceId has been terminated")
        deviceIdToActor -= deviceId
        this
    }

  override def onSignal: PartialFunction[Signal, Behavior[DeviceGroupCommand]] = {
    case PostStop =>
      context.log.info(s"DeviceGroup $groupId stopped")
      this
  }
}
