package practice.akka.iot

import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors, TimerScheduler }
import akka.actor.typed.{ ActorRef, Behavior }
import practice.akka.iot.Device.DeviceCommand
import practice.akka.iot.DeviceGroupQuery.DeviceGroupQueryCommand

import scala.concurrent.duration.FiniteDuration

object DeviceGroupQuery {

  def apply(deviceIdToActor: Map[String, ActorRef[DeviceCommand]],
            requestId: Long,
            requester: ActorRef[DeviceManager.RespondAllTemperatures],
            timeout: FiniteDuration): Behavior[DeviceGroupQueryCommand] = {
    Behaviors.setup(context => {
      Behaviors.withTimers { timers =>
        new DeviceGroupQuery(deviceIdToActor, requestId, requester, timeout, context, timers)
      }
    })
  }

  trait DeviceGroupQueryCommand

  private case object CollectionTimeout extends DeviceGroupQueryCommand

  final case class WrappedRespondTemperature(response: Device.RespondTemperature) extends DeviceGroupQueryCommand

  private final case class DeviceTerminated(deviceId: String) extends DeviceGroupQueryCommand
}

class DeviceGroupQuery(deviceIdToActor: Map[String, ActorRef[Device.DeviceCommand]],
                       requestId: Long,
                       requester: ActorRef[DeviceManager.RespondAllTemperatures],
                       timeout: FiniteDuration,
                       context: ActorContext[DeviceGroupQueryCommand],
                       timers: TimerScheduler[DeviceGroupQueryCommand])
    extends AbstractBehavior[DeviceGroupQueryCommand](context) {

  import DeviceGroupQuery._
  import DeviceManager.DeviceNotAvailable
  import DeviceManager.DeviceTimedOut
  import DeviceManager.RespondAllTemperatures
  import DeviceManager.Temperature
  import DeviceManager.TemperatureNotAvailable
  import DeviceManager.TemperatureReading

  timers.startSingleTimer(CollectionTimeout, CollectionTimeout, timeout)

  private val respondTemperatureAdapter = context.messageAdapter(WrappedRespondTemperature.apply)

  private var repliesSoFar = Map.empty[String, TemperatureReading]

  private var stillWaiting = deviceIdToActor.keySet

  deviceIdToActor.foreach {
    case (deviceId, device) =>
      context.watchWith(device, DeviceTerminated(deviceId))
      device ! Device.ReadTemperature(0, respondTemperatureAdapter)
  }

  override def onMessage(msg: DeviceGroupQueryCommand): Behavior[DeviceGroupQueryCommand] =
    msg match {
      case WrappedRespondTemperature(response) => onRespondTemperature(response)
      case DeviceTerminated(deviceId)          => onDeviceTerminated(deviceId)
      case CollectionTimeout                   => onCollectionTimeout()
    }

  private def onRespondTemperature(response: Device.RespondTemperature): Behavior[DeviceGroupQueryCommand] = {

    val reading = response.value match {
      case Some(value) => Temperature(value)
      case None        => TemperatureNotAvailable
    }

    val deviceId = response.deviceId
    repliesSoFar += (deviceId -> reading)
    stillWaiting -= deviceId

    respondWhenAllCollected()
  }

  private def onDeviceTerminated(deviceId: String): Behavior[DeviceGroupQueryCommand] = {

    if (stillWaiting(deviceId)) {
      repliesSoFar += (deviceId -> DeviceNotAvailable)
      stillWaiting -= deviceId
    }

    respondWhenAllCollected()
  }

  private def onCollectionTimeout(): Behavior[DeviceGroupQueryCommand] = {

    repliesSoFar ++= stillWaiting.map(deviceId => deviceId -> DeviceTimedOut)
    stillWaiting = Set.empty

    respondWhenAllCollected()
  }

  private def respondWhenAllCollected(): Behavior[DeviceGroupQueryCommand] = {
    if (stillWaiting.isEmpty) {
      requester ! RespondAllTemperatures(requestId, repliesSoFar)
      Behaviors.stopped
    } else {
      this
    }
  }
}
