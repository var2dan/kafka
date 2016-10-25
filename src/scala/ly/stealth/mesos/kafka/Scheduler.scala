/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ly.stealth.mesos.kafka

import net.elodina.mesos.util.{Period, Repr, Strings, Version}
import java.util.concurrent.ConcurrentHashMap
import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.{MesosSchedulerDriver, SchedulerDriver}
import java.util
import com.google.protobuf.ByteString
import java.util.{Collections, Date}
import ly.stealth.mesos.kafka.json.JsonUtil
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import org.apache.log4j._
import scala.collection.mutable
import scala.util.Random

abstract class OfferResult
object OfferResult {
  case class Decline(reason: String, duration: Int = 5) extends OfferResult {
    def +(other: Decline) =
      Decline(
        Seq(reason, other.reason).filter(r => r != null && r.nonEmpty).mkString("\n"),
        duration.min(other.duration)
      )
  }
  case class Accept() extends OfferResult

  def neverMatch(reason: String) = Decline(reason, 60 * 60)
  def eventuallyMatch(reason: String, howLong: Int) = Decline(reason, howLong)
}


object Scheduler extends org.apache.mesos.Scheduler {
  private val logger: Logger = Logger.getLogger(this.getClass)
  val version: Version = new Version("0.9.5.1")

  lazy val cluster: Cluster = Cluster.load()
  private var driver: SchedulerDriver = null

  val logs = new ConcurrentHashMap[Long, Option[String]]()

  private var canSuppressOffers = false
  private var offersAreSuppressed = false

  private[kafka] def newExecutor(broker: Broker): ExecutorInfo = {
    var cmd = "java -cp " + HttpServer.jar.getName
    cmd += " -Xmx" + broker.heap + "m"
    if (broker.jvmOptions != null) cmd += " " + broker.jvmOptions.replace("$id", broker.id)

    if (Config.debug) cmd += " -Ddebug"
    cmd += " ly.stealth.mesos.kafka.Executor"

    val commandBuilder = CommandInfo.newBuilder
    if (Config.jre != null) {
      commandBuilder.addUris(CommandInfo.URI.newBuilder().setValue(Config.api + "/jre/" + Config.jre.getName))
      cmd = "jre/bin/" + cmd
    }

    val env = new mutable.HashMap[String, String]()
    env("MESOS_SYSLOG_TAG") = Config.frameworkName + "-" + broker.id
    if (broker.syslog) env("MESOS_SYSLOG") = "true"

    val envBuilder = Environment.newBuilder()
    for ((name, value) <- env)
      envBuilder.addVariables(Variable.newBuilder().setName(name).setValue(value))
    commandBuilder.setEnvironment(envBuilder)

    commandBuilder
      .addUris(CommandInfo.URI.newBuilder().setValue(Config.api + "/jar/" + HttpServer.jar.getName).setExtract(false))
      .addUris(CommandInfo.URI.newBuilder().setValue(Config.api + "/kafka/" + HttpServer.kafkaDist.getName))
      .setValue(cmd)

    ExecutorInfo.newBuilder()
      .setExecutorId(ExecutorID.newBuilder.setValue(Broker.nextExecutorId(broker)))
      .setCommand(commandBuilder)
      .setName("kafka.broker")
      .setSource(Config.frameworkName + ".broker." + broker.id)
      .build()
  }

  private[kafka] def newTask(broker: Broker, offer: Offer, reservation: Broker.Reservation): TaskInfo = {
    def taskData: ByteString = {
      var defaults: Map[String, String] = Map(
        "broker.id" -> broker.id,
        "port" -> ("" + reservation.port),
        "log.dirs" -> "kafka-logs",
        "log.retention.bytes" -> ("" + 10l * 1024 * 1024 * 1024),

        "zookeeper.connect" -> Config.zk,
        "host.name" -> offer.getHostname
      )

      if (HttpServer.kafkaVersion.compareTo(new Version("0.9")) >= 0)
        defaults += ("listeners" -> s"PLAINTEXT://:${reservation.port}")

      if (reservation.volume != null)
        defaults += ("log.dirs" -> "data/kafka-logs")

      val launchConfig = LaunchConfig(
        broker.id,
        broker.options,
        broker.syslog,
        broker.log4jOptions,
        broker.bindAddress,
        defaults)
      ByteString.copyFrom(JsonUtil.toJsonBytes(launchConfig))
    }

    val taskBuilder: TaskInfo.Builder = TaskInfo.newBuilder
      .setName(Config.frameworkName + "-" + broker.id)
      .setTaskId(TaskID.newBuilder.setValue(Broker.nextTaskId(broker)).build)
      .setSlaveId(offer.getSlaveId)
      .setData(taskData)
      .setExecutor(newExecutor(broker))

    taskBuilder.addAllResources(reservation.toResources)
    taskBuilder.build
  }

  def registered(driver: SchedulerDriver, id: FrameworkID, master: MasterInfo): Unit = {
    logger.info("[registered] framework:" + Repr.id(id.getValue) + " master:" + Repr.master(master))

    cluster.frameworkId = id.getValue
    cluster.save()

    this.driver = driver
    checkMesosVersion(master)
    reconcileTasksIfRequired(force = true)
  }

  def reregistered(driver: SchedulerDriver, master: MasterInfo): Unit = {
    logger.info("[reregistered] master:" + Repr.master(master))
    this.driver = driver
    reconcileTasksIfRequired(force = true)
  }

  def resourceOffers(driver: SchedulerDriver, offers: util.List[Offer]): Unit = {
    logger.debug("[resourceOffers]\n" + Repr.offers(offers))
    syncBrokers(offers)
  }

  def offerRescinded(driver: SchedulerDriver, id: OfferID): Unit = {
    logger.info("[offerRescinded] " + Repr.id(id.getValue))
  }

  def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
    logger.info("[statusUpdate] " + Repr.status(status))
    onBrokerStatus(status)
  }

  def frameworkMessage(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, data: Array[Byte]): Unit = {
    if (logger.isTraceEnabled)
      logger.trace("[frameworkMessage] executor:" + Repr.id(executorId.getValue) + " slave:" + Repr.id(slaveId.getValue) + " data: " + new String(data))
    else if (logger.isDebugEnabled)
      logger.debug(s"[frameworkMessage] executor: ${Repr.id(executorId.getValue)} slave: ${Repr.id(slaveId.getValue)}")

    val broker = cluster.getBroker(Broker.idFromExecutorId(executorId.getValue))

    try {
      val msg = JsonUtil.fromJson[FrameworkMessage](data)
      msg.metrics.foreach(metrics => {
        if (broker != null && broker.active) {
          broker.metrics = metrics
        }
      })

      msg.log.foreach(logResponse => {
        if (broker != null
            && broker.active
            && broker.task != null
            && broker.task.running
            && logs.containsKey(logResponse.requestId)) {
          logs.put(logResponse.requestId, Some(logResponse.content))
        }
      })
    } catch {
      case e: IllegalArgumentException =>
        logger.warn("Unable to parse framework message as JSON", e)
    }
  }

  def disconnected(driver: SchedulerDriver): Unit = {
    logger.info("[disconnected]")
    this.driver = null
  }

  def slaveLost(driver: SchedulerDriver, id: SlaveID): Unit = {
    logger.info("[slaveLost] " + Repr.id(id.getValue))
  }

  def executorLost(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, status: Int): Unit = {
    logger.info("[executorLost] executor:" + Repr.id(executorId.getValue) + " slave:" + Repr.id(slaveId.getValue) + " status:" + status)
  }

  def error(driver: SchedulerDriver, message: String): Unit = {
    logger.info("[error] " + message)
  }

  def activateBroker(broker: Broker): Unit = {
    broker.active = true
    pauseOrResumeOffers()
  }

  private[kafka] def syncBrokers(offers: util.List[Offer]): Unit = {
    var didSomething = tryLaunchBrokers(offers)
    didSomething |= tryStopInactiveBrokers()
    didSomething |= reconcileTasksIfRequired()
    if (didSomething) {
      cluster.save()
      logger.info("Saving cluster state")
    }
  }

  private def tryLaunchBrokers(offers: Seq[Offer]): Boolean = {
    val results = offers.map(o => o -> tryAcceptOffer(o))
    // Decline unmatched offers
    results.foreach({
      case (offer, decline: OfferResult.Decline) =>
        val jitter = (decline.duration / 3) * (Random.nextDouble() - .5)
        val fb = Filters.newBuilder().setRefuseSeconds(decline.duration + jitter)
        driver.declineOffer(offer.getId, fb.build())
      case _ =>
    })

    if (logger.isDebugEnabled)
      logger.debug(results.map(r => s"${r._1.getId.getValue} -> ${r._2}").mkString("\n"))

    results.exists({
      case (_, _: OfferResult.Accept) => true
      case _ => false
    })
  }

  def tryStopInactiveBrokers(): Boolean = {
    var didSomething = false
    for (broker <- cluster.getBrokers) {
      if (broker.shouldStop) {
        logger.info(s"Stopping broker ${broker.id}: killing task ${broker.task.id}")
        driver.killTask(TaskID.newBuilder.setValue(broker.task.id).build)
        broker.task.state = Broker.State.STOPPING
        didSomething = true
      }
    }
    didSomething
  }

  private[kafka] def tryAcceptOffer(offer: Offer): OfferResult = {
    if (isReconciling) return OfferResult.Decline("reconciling", 5)
    val now = new Date()
    var declineInfo = OfferResult.neverMatch("")

    for (broker <- cluster.getBrokers.filter(_.shouldStart(offer.getHostname))) {
      broker.matches(offer, now, otherTasksAttributes) match {
        case _: OfferResult.Accept =>
          launchTask(broker, offer)
          return OfferResult.Accept()
        case reason: OfferResult.Decline => declineInfo +=
          OfferResult.Decline(s"broker ${broker.id}: ${reason.reason}", reason.duration)
      }
    }

    declineInfo
  }

  private[kafka] def onBrokerStatus(status: TaskStatus): Unit = {
    val broker = cluster.getBroker(Broker.idFromTaskId(status.getTaskId.getValue))

    status.getState match {
      case TaskState.TASK_RUNNING =>
        onBrokerStarted(broker, status)
      case TaskState.TASK_LOST | TaskState.TASK_FINISHED |
           TaskState.TASK_FAILED | TaskState.TASK_KILLED |
           TaskState.TASK_ERROR =>
        onBrokerStopped(broker, status)
      case _ => logger.warn("Got unexpected task state: " + status.getState)
    }

    cluster.save()
    pauseOrResumeOffers()
  }

  private def pauseOrResumeOffers(): Unit = {
    val clusterIsSteadyState = cluster.getBrokers.asScala.forall(_.isSteadyState)
    // If all brokers are steady state we can request mesos to stop sending offers.
    if (!this.offersAreSuppressed && clusterIsSteadyState) {
      if (canSuppressOffers) {
        // Our version of mesos supports suppressOffers, so use it.
        val result = driver.suppressOffers()
        if (result == Status.DRIVER_RUNNING) {
          logger.info("Cluster is now stable, offers are suppressed")
          this.offersAreSuppressed = true
        }
        else {
          logger.error(s"Error suppressing offers, driver returned '$result'")
        }
      }
      else {
        // No support for suppress offers, noop it.
        this.offersAreSuppressed = true
      }
    }
    // Else, if offers are suppressed, and we are no longer steady-state, resume offers.
    else if (!clusterIsSteadyState && offersAreSuppressed) {
      if (driver.reviveOffers() == Status.DRIVER_RUNNING) {
        logger.info("Cluster is no longer stable, resuming offers.")
        this.offersAreSuppressed = false
      }
    }
  }

  private[kafka] def onBrokerStarted(broker: Broker, status: TaskStatus): Unit = {
    if (broker == null || broker.task == null || broker.task.id != status.getTaskId.getValue) {
      logger.info(s"Got ${status.getState} for unknown/stopped broker, killing task ${status.getTaskId}")
      driver.killTask(status.getTaskId)
      return
    }

    if (broker.task.reconciling)
      logger.info(s"Finished reconciling of broker ${broker.id}, task ${broker.task.id}")

    broker.task.state = Broker.State.RUNNING
    if (status.getData.size() > 0)
      broker.task.endpoint = new Broker.Endpoint(status.getData.toStringUtf8)
    broker.registerStart(broker.task.hostname)
  }

  private[kafka] def onBrokerStopped(broker: Broker, status: TaskStatus, now: Date = new Date()): Unit = {
    if (broker == null) {
      logger.info(s"Got ${status.getState} for unknown broker, ignoring it")
      return
    }

    broker.task = null
    val failed = broker.active && status.getState != TaskState.TASK_FINISHED && status.getState != TaskState.TASK_KILLED
    broker.registerStop(now, failed)

    if (failed) {
      var msg = s"Broker ${broker.id} failed ${broker.failover.failures}"
      if (broker.failover.maxTries != null) msg += "/" + broker.failover.maxTries

      if (!broker.failover.isMaxTriesExceeded) {
        msg += ", waiting " + broker.failover.currentDelay
        msg += ", next start ~ " + Repr.dateTime(broker.failover.delayExpires)
      } else {
        broker.active = false
        msg += ", failure limit exceeded"
        msg += ", deactivating broker"
      }

      logger.info(msg)
    }

    broker.metrics = null
    broker.needsRestart = false
  }

  private def isReconciling: Boolean = cluster.getBrokers.exists(b => b.task != null && b.task.reconciling)

  private[kafka] def launchTask(broker: Broker, offer: Offer): TaskID = {
    broker.needsRestart = false

    val reservation = broker.getReservation(offer)
    val task_ = newTask(broker, offer, reservation)
    val id = task_.getTaskId.getValue

    val attributes = offer.getAttributesList
      .asScala
      .filter(_.hasText)
      .map(a => a.getName -> a.getText.getValue)
      .toMap

    driver.launchTasks(util.Arrays.asList(offer.getId), util.Arrays.asList(task_))
    broker.task = Broker.Task(
      id,
      task_.getSlaveId.getValue,
      task_.getExecutor.getExecutorId.getValue,
      offer.getHostname,
      attributes)
    logger.info(s"Starting broker ${broker.id}: launching task $id by offer ${offer.getHostname + Repr.id(offer.getId.getValue)}\n ${Repr.task(task_)}")
    TaskID.newBuilder().setValue(id).build()
  }

  def forciblyStopBroker(broker: Broker): Unit = {
    if (driver != null && broker.task != null) {
      logger.info(s"Stopping broker ${broker.id} forcibly: sending 'stop' message")

      driver.sendFrameworkMessage(
        ExecutorID.newBuilder().setValue(broker.task.executorId).build(),
        SlaveID.newBuilder().setValue(broker.task.slaveId).build(),
        "stop".getBytes
      )
    }
  }

  private[kafka] var reconciles: Int = 0
  private[kafka] var reconcileTime: Date = null

  private[kafka] def reconcileTasksIfRequired(force: Boolean = false, now: Date = new Date()): Boolean = {
    var didSomething = false
    if (reconcileTime != null && now.getTime - reconcileTime.getTime < Config.reconciliationTimeout.ms)
      return false

    if (!isReconciling) reconciles = 0
    reconciles += 1
    reconcileTime = now

    if (reconciles > Config.reconciliationAttempts) {
      for (broker <- cluster.getBrokers.filter(b => b.task != null && b.task.reconciling)) {
        logger.info(s"Reconciling exceeded ${Config.reconciliationAttempts} tries for broker ${broker.id}, sending killTask for task ${broker.task.id}")
        driver.killTask(TaskID.newBuilder().setValue(broker.task.id).build())
        broker.task = null
        didSomething = true
      }

      return didSomething
    }

    val statuses = new util.ArrayList[TaskStatus]

    for (broker <- cluster.getBrokers.filter(_.task != null))
      if (force || broker.task.reconciling) {
        broker.task.state = Broker.State.RECONCILING
        logger.info(s"Reconciling $reconciles/${Config.reconciliationAttempts} state of broker ${broker.id}, task ${broker.task.id}")

        statuses.add(TaskStatus.newBuilder()
          .setTaskId(TaskID.newBuilder().setValue(broker.task.id))
          .setState(TaskState.TASK_STAGING)
          .build()
        )
        didSomething = true
      }

    if (force || !statuses.isEmpty)
      driver.reconcileTasks(if (force) Collections.emptyList() else statuses)

    didSomething
  }

  private def checkMesosVersion(master: MasterInfo): Unit = {
    val version = if (master.getVersion != null)
      new Version(master.getVersion)
    else {
      logger.warn("Unable to detect mesos version, mesos < 0.23 is unsupported, proceed with caution.")
      new Version("0.22.1")
    }

    val hasSuppressOffers = new Version("0.25.0")
    if (version.compareTo(hasSuppressOffers) >= 0) {
      logger.info("Enabling offer suppression")
      canSuppressOffers = true
    }
  }

  private[kafka] def otherTasksAttributes(name: String): util.Collection[String] = {
    def value(task: Broker.Task, name: String): String = {
      if (name == "hostname") return task.hostname
      task.attributes.get(name).orNull
    }

    val values = new util.ArrayList[String]()
    for (broker <- cluster.getBrokers)
      if (broker.task != null) {
        val v = value(broker.task, name)
        if (v != null) values.add(v)
      }

    values
  }

  def start() {
    initLogging()
    logger.info(s"Starting ${getClass.getSimpleName}:\n$Config")

    HttpServer.start()

    val frameworkBuilder = FrameworkInfo.newBuilder()
    frameworkBuilder.setUser(if (Config.user != null) Config.user else "")
    if (cluster.frameworkId != null) frameworkBuilder.setId(FrameworkID.newBuilder().setValue(cluster.frameworkId))
    frameworkBuilder.setRole(Config.frameworkRole)

    frameworkBuilder.setName(Config.frameworkName)
    frameworkBuilder.setFailoverTimeout(Config.frameworkTimeout.ms / 1000)
    frameworkBuilder.setCheckpoint(true)

    var credsBuilder: Credential.Builder = null
    if (Config.principal != null && Config.secret != null) {
      frameworkBuilder.setPrincipal(Config.principal)

      credsBuilder = Credential.newBuilder()
      credsBuilder.setPrincipal(Config.principal)
      credsBuilder.setSecret(Config.secret)
    }

    val driver =
      if (credsBuilder != null) new MesosSchedulerDriver(Scheduler, frameworkBuilder.build, Config.master, credsBuilder.build)
      else new MesosSchedulerDriver(Scheduler, frameworkBuilder.build, Config.master)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() = HttpServer.stop()
    })

    val status = if (driver.run eq Status.DRIVER_STOPPED) 0 else 1
    System.exit(status)
  }

  def stop(): Unit = {
    if (driver != null) {
      // Warning: stop(false) and stop() are dangerous, calling them will destroy the framework
      // and kill all running tasks / executors.
      driver.stop(true)
    }
  }

  def kill(): Unit = {
    System.exit(1)
  }

  private def initLogging() {
    HttpServer.initLogging()
    BasicConfigurator.resetConfiguration()

    val root = Logger.getRootLogger
    root.setLevel(Level.INFO)

    Logger.getLogger("org.apache.zookeeper").setLevel(Level.WARN)
    Logger.getLogger("org.I0Itec.zkclient").setLevel(Level.WARN)

    val logger = Logger.getLogger(Scheduler.getClass)
    logger.setLevel(if (Config.debug) Level.DEBUG else Level.INFO)

    val layout = new PatternLayout("%d [%t] %-5p %c %x - %m%n")

    var appender: Appender = null
    if (Config.log == null) appender = new ConsoleAppender(layout)
    else appender = new DailyRollingFileAppender(layout, Config.log.getPath, "'.'yyyy-MM-dd")
    
    root.addAppender(appender)
  }

  def requestBrokerLog(broker: Broker, name: String, lines: Int): Long = {
    var requestId: Long = -1
    if (driver != null) {
      requestId = System.currentTimeMillis()
      logs.put(requestId, None)
      val executorId = ExecutorID.newBuilder().setValue(broker.task.executorId).build()
      val slaveId = SlaveID.newBuilder().setValue(broker.task.slaveId).build()

      driver.sendFrameworkMessage(executorId, slaveId, LogRequest(requestId, lines, name).toString.getBytes)
    }
    requestId
  }

  def receivedLog(requestId: Long): Boolean = logs.get(requestId).isDefined

  def logContent(requestId: Long): String = logs.get(requestId).get

  def removeLog(requestId: Long): Option[String] = logs.remove(requestId)
}
