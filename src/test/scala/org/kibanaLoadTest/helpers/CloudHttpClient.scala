package org.kibanaLoadTest.helpers

import java.time.Instant

import com.typesafe.config.Config
import org.slf4j.{Logger, LoggerFactory}
import jodd.util.ThreadUtil.sleep
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils
import spray.json.lenses.JsonLenses._
import spray.json.DefaultJsonProtocol._

class CloudHttpClient {
  private val DEPLOYMENT_READY_TIMOEOUT = 5 * 60 * 1000 // 5 min
  private val DEPLOYMENT_POLLING_INTERVAL = 20 * 1000 // 20 sec
  private val httpClient = HttpClientBuilder.create.build
  private val deployPayloadTemplate = "cloudPayload/createDeployment.json"
  private val baseUrl = "https://staging.found.no/api/v1/deployments"
  private val apiKey = Option(System.getenv("API_KEY"))

  val logger: Logger = LoggerFactory.getLogger("httpClient")

  def getApiKey: String = {
    if (this.apiKey.isEmpty) {
      throw new RuntimeException(
        "API_KEY variable is required to interact with Cloud service"
      )
    } else this.apiKey.get
  }

  def preparePayload(stackVersion: String, config: Config): String = {
    logger.info(
      s"preparePayload: Using $deployPayloadTemplate payload template"
    )
    val template = Helper.loadJsonString(deployPayloadTemplate)
    logger.info(
      s"preparePayload: Stack version $stackVersion with ${config.toString} configuration"
    )

    val payload =
      template
        .update(
          Symbol("name") ! set[String](
            s"load-testing-${Instant.now.toEpochMilli}"
          )
        )
        .update(
          Symbol("resources") / Symbol("elasticsearch") / element(0) / Symbol(
            "plan"
          ) / Symbol("elasticsearch") / Symbol("version") ! set[String](
            stackVersion
          )
        )
        .update(
          Symbol("resources") / Symbol("elasticsearch") / element(0) / Symbol(
            "plan"
          ) / Symbol("cluster_topology") / element(0) / Symbol("size") / Symbol(
            "value"
          ) ! set[String](config.getString("elasticsearch.deployment_template"))
        )
        .update(
          Symbol("resources") / Symbol("elasticsearch") / element(0) / Symbol(
            "plan"
          ) / Symbol("cluster_topology") / element(0) / Symbol("size") / Symbol(
            "value"
          ) ! set[Int](config.getInt("elasticsearch.memory"))
        )
        .update(
          Symbol("resources") / Symbol("kibana") / element(0) / Symbol(
            "plan"
          ) / Symbol("kibana") / Symbol("version") ! set[String](stackVersion)
        )
        .update(
          Symbol("resources") / Symbol("kibana") / element(0) / Symbol(
            "plan"
          ) / Symbol("cluster_topology") / element(0) / Symbol("size") / Symbol(
            "value"
          ) ! set[Int](config.getInt("kibana.memory"))
        )
        .update(
          Symbol("resources") / Symbol("apm") / element(0) / Symbol(
            "plan"
          ) / Symbol("apm") / Symbol("version") ! set[String](stackVersion)
        )

    payload.toString
  }

  def createDeployment(payload: String): Map[String, String] = {
    logger.info(s"createDeployment: Creating new deployment")
    val createRequest = new HttpPost(baseUrl)
    createRequest.addHeader("Authorization", s"ApiKey $getApiKey")
    createRequest.setEntity(new StringEntity(payload))
    val response = httpClient.execute(createRequest)
    val responseString = EntityUtils.toString(response.getEntity)
    val meta = Map(
      "deploymentId" -> responseString.extract[String](Symbol("id")),
      "username" -> responseString.extract[String](
        Symbol("resources") / element(0) / Symbol("credentials") / Symbol(
          "username"
        )
      ),
      "password" -> responseString.extract[String](
        Symbol("resources") / element(0) / Symbol("credentials") / Symbol(
          "password"
        )
      )
    )

    logger.info(
      s"createDeployment: deployment ${meta("deploymentId")} is created"
    )

    meta
  }

  def getDeploymentStateInfo(id: String): String = {
    val getStateRequest: HttpGet = new HttpGet(s"$baseUrl/$id")
    getStateRequest.addHeader("Authorization", s"ApiKey $getApiKey")
    val response = httpClient.execute(getStateRequest)
    EntityUtils.toString(response.getEntity)
  }

  def getInstanceStatus(deploymentId: String): Map[String, String] = {
    val jsonString = getDeploymentStateInfo(deploymentId)
    val items = Array("kibana", "elasticsearch", "apm")

    items
      .map(item => {
        val status = jsonString.extract[String](
          Symbol("resources") / item / element(0) / Symbol("info") / Symbol(
            "status"
          )
        )
        item -> status
      })
      .toMap
  }

  def getKibanaUrl(deploymentId: String): String = {
    val jsonString = getDeploymentStateInfo(deploymentId)
    jsonString.extract[String](
      Symbol("resources") / Symbol("kibana") / element(0) / Symbol(
        "info"
      ) / Symbol("metadata") / Symbol("service_url")
    )
  }

  def waitForClusterToStart(
      deploymentId: String,
      fn: String => Map[String, String] = getInstanceStatus,
      timeout: Int = DEPLOYMENT_READY_TIMOEOUT,
      interval: Int = DEPLOYMENT_POLLING_INTERVAL
  ): Unit = {
    var started = false
    var timeLeft = timeout
    var poolingInterval = interval
    logger.info(
      s"waitForClusterToStart: waitTime ${timeout}ms, poolingInterval ${poolingInterval}ms"
    )
    while (!started && timeLeft > 0) {
      var statuses = Map.empty[String, String]
      try statuses = fn(deploymentId)
      catch {
        case ex: Exception =>
          logger.error(ex.getMessage)
      }
      if (statuses.isEmpty || statuses.values.exists(s => s != "started")) {
        logger.info(
          s"waitForClusterToStart: Deployment is in progress... ${statuses.toString()}"
        )
        timeLeft -= poolingInterval
        sleep(poolingInterval)
      } else {
        logger.info("waitForClusterToStart: Deployment is ready!")
        started = true
      }
    }

    if (!started)
      throw new RuntimeException(
        s"Deployment $deploymentId was not ready after $timeout ms"
      )
  }

  def deleteDeployment(id: String): Unit = {
    logger.info(s"deleteDeployment: Deployment $id")
    val deleteRequest = new HttpPost(
      "%s/%s/_shutdown?hide=true&skip_snapshot=true".format(baseUrl, id)
    )
    deleteRequest.addHeader("Authorization", s"ApiKey $getApiKey")
    val response = httpClient.execute(deleteRequest)
    logger.info(
      s"deleteDeployment: Finished with status code ${response.getStatusLine.getStatusCode}"
    )
  }

}
