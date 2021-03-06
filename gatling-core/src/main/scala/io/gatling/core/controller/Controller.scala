/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.core.controller

import java.util.UUID.randomUUID

import io.gatling.core.session.Session

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure => SFailure, Success => SSuccess }

import com.typesafe.scalalogging.StrictLogging

import akka.actor.ActorRef
import akka.actor.ActorDSL.actor
import akka.util.Timeout
import io.gatling.core.action.UserEnd
import io.gatling.core.akka.{ AkkaDefaults, BaseActor }
import io.gatling.core.controller.throttle.{ Throttler, ThrottlingProtocol }
import io.gatling.core.result.message.{ End, Start }
import io.gatling.core.result.writer.{ DataWriter, RunMessage, UserMessage }
import io.gatling.core.scenario.Scenario
import io.gatling.core.util.TimeHelper._

case class Timings(maxDuration: Option[FiniteDuration], globalThrottling: Option[ThrottlingProtocol], perScenarioThrottlings: Map[String, ThrottlingProtocol])

object Controller extends AkkaDefaults with StrictLogging {

  private var _instance: Option[ActorRef] = None

  def start(): Unit = {
    _instance = Some(actor("controller")(new Controller))
    system.registerOnTermination(_instance = None)
    UserEnd.start()
  }

  def !(message: Any): Unit =
    _instance match {
      case Some(c) => c ! message
      case None    => logger.debug("Controller hasn't been started")
    }

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] = _instance match {
    case Some(c) => c.ask(message)(timeout)
    case None    => throw new UnsupportedOperationException("Controller has not been started")
  }
}

class Controller extends BaseActor {

  var scenarios: Seq[Scenario] = _
  var totalNumberOfUsers = 0
  val activeUsers = mutable.Map.empty[String, UserMessage]
  var finishedUsers = 0
  var launcher: ActorRef = _
  var runId: String = _
  var timings: Timings = _

  val uninitialized: Receive = {

    case Run(simulation, simulationId, description, runTimings) =>
      // important, initialize time reference
      val timeRef = NanoTimeReference
      launcher = sender()
      timings = runTimings
      scenarios = simulation.scenarios

      if (scenarios.isEmpty)
        launcher ! SFailure(new IllegalArgumentException(s"Simulation ${simulation.getClass} doesn't have any configured scenario"))

      else if (scenarios.map(_.name).toSet.size != scenarios.size)
        launcher ! SFailure(new IllegalArgumentException(s"Scenario names must be unique but found a duplicate"))

      else {
        totalNumberOfUsers = scenarios.map(_.injectionProfile.users).sum
        logger.info(s"Total number of users : $totalNumberOfUsers")

        val runMessage = RunMessage(simulation.getClass.getName, simulationId, nowMillis, description)
        runId = runMessage.runId
        DataWriter.init(simulation.assertions, runMessage, scenarios, self)
        context.become(waitingForDataWriterToInit)
      }
  }

  def waitingForDataWriterToInit: Receive = {

    case DataWritersInitialized(result) => result match {
      case f: SFailure[_] => launcher ! f

      case _ =>
        val userIdRoot = math.abs(randomUUID.getMostSignificantBits) + "-"

        val (userStreams, _) = scenarios.foldLeft((Map.empty[String, UserStream], 0)) { (streamsAndOffset, scenario) =>
          val (streams, offset) = streamsAndOffset

          val stream = scenario.injectionProfile.allUsers.zipWithIndex
          val userStream = UserStream(scenario, offset, stream)

          (streams + (scenario.name -> userStream), offset + scenario.injectionProfile.users)
        }

        val batcher = batchSchedule(userIdRoot, nowMillis, 10 seconds) _
        logger.debug("Launching All Scenarios")
        userStreams.values.foreach(batcher)
        logger.debug("Finished Launching scenarios executions")

        timings.maxDuration
          .orElse(timings.globalThrottling.map(_.duration))
          .orElse(if (timings.perScenarioThrottlings.nonEmpty) Some(timings.perScenarioThrottlings.values.map(_.duration).min) else None)
          .foreach {
            logger.debug("Setting up max duration")
            scheduler.scheduleOnce(_) {
              self ! ForceTermination(None)
            }
          }

        val initializedState = initialized(userStreams, batcher)

        val newState = if (timings.globalThrottling.isDefined || timings.perScenarioThrottlings.nonEmpty) {
          logger.debug("Setting up throttling")
          scheduler.schedule(0 seconds, 1 seconds, self, OneSecondTick)
          val throttler = new Throttler(timings.globalThrottling, timings.perScenarioThrottlings)
          throttling(throttler).orElse(initializedState)
        } else
          initializedState

        context.become(newState)
    }

    case m => logger.error(s"Shouldn't happen. Ignore message $m while waiting for DataWriter to initialize")
  }

  def throttling(throttler: Throttler): Receive = {
    case OneSecondTick                           => throttler.flushBuffer()
    case ThrottledRequest(scenarioName, request) => throttler.send(scenarioName, request)
  }

  case class UserStream(scenario: Scenario, offset: Int, stream: Iterator[(FiniteDuration, Int)])

  def batchSchedule(userIdRoot: String, start: Long, batchWindow: FiniteDuration)(userStream: UserStream): Unit = {

    val scenario = userStream.scenario
    val stream = userStream.stream

      def startUser(i: Int): Unit = {
        val session = Session(scenarioName = scenario.name,
          userId = userIdRoot + (i + userStream.offset),
          userEnd = scenario.protocols.userEnd)
        // FIXME why not directly session?
        self ! UserMessage(session.scenarioName, session.userId, Start, session.startDate, 0L)
        scenario.entryPoint ! session
      }

    if (stream.hasNext) {
      val batchTimeOffset = (nowMillis - start).millis
      val nextBatchTimeOffset = batchTimeOffset + batchWindow

      var continue = true

      while (stream.hasNext && continue) {

        val (startingTime, index) = stream.next
        val delay = startingTime - batchTimeOffset
        continue = startingTime < nextBatchTimeOffset

        if (continue && delay <= ZeroMs)
          startUser(index)

        else
          // Reduce the starting time to the millisecond precision to avoid flooding the scheduler
          scheduler.scheduleOnce(toMillisPrecision(delay)) {
            startUser(index)
          }
      }

      // schedule next batch
      if (stream.hasNext)
        self ! ScheduleNextUserBatch(scenario.name)
    }
  }

  def initialized(userStreams: Map[String, UserStream], batcher: UserStream => Unit): Receive = {

      def dispatchUserEndToDataWriter(userMessage: UserMessage): Unit = {
        logger.info(s"End user #${userMessage.userId}")
        DataWriter.dispatch(userMessage)
      }

      def becomeTerminating(exception: Option[Exception]): Unit = {
        DataWriter.terminate(self)
        context.become(waitingForDataWriterToTerminate(exception))
      }

    {
      case userMessage @ UserMessage(_, userId, event, _, _) => event match {
        case Start =>
          activeUsers += userId -> userMessage
          logger.info(s"Start user #${userMessage.userId}")
          DataWriter.dispatch(userMessage)

        case End =>
          finishedUsers += 1
          activeUsers -= userId
          dispatchUserEndToDataWriter(userMessage)
          if (finishedUsers == totalNumberOfUsers)
            becomeTerminating(None)
      }

      case ScheduleNextUserBatch(scenarioName) =>
        val userStream = userStreams(scenarioName)
        logger.info(s"Starting new user batch for $scenarioName")
        batcher(userStream)

      case ForceTermination(exception) =>
        // flush all active users
        val now = nowMillis
        for (activeUser <- activeUsers.values) {
          dispatchUserEndToDataWriter(activeUser.copy(event = End, endDate = now))
        }
        becomeTerminating(exception)
    }
  }

  def waitingForDataWriterToTerminate(exception: Option[Exception]): Receive = {
    case DataWritersTerminated(result) =>
      exception match {
        case Some(e) => launcher ! SFailure(e)
        case _       => launcher ! SSuccess(runId)
      }
    case m => logger.debug(s"Ignore message $m while waiting for DataWriter to terminate")
  }

  def receive = uninitialized
}
