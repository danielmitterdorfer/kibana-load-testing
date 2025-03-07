package org.kibanaLoadTest.simulation.cloud

import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import org.kibanaLoadTest.scenario.{Login, Visualize}
import org.kibanaLoadTest.simulation.BaseSimulation

class TSVBGaugeJourney extends BaseSimulation {
  val scenarioName = s"Cloud gauge journey ${appConfig.buildVersion}"

  props.maxUsers = 600

  val scn: ScenarioBuilder = scenario(scenarioName)
    .exec(
      Login
        .doLogin(
          appConfig.isSecurityEnabled,
          appConfig.loginPayload,
          appConfig.loginStatusCode
        )
        .pause(5)
    )
    .exec(
      Visualize
        .load(
          "tsvb",
          "b80e6540-b891-11e8-a6d9-e546fe2bba5f",
          "data/visualize/gauge_sold_per_day.json",
          appConfig.baseUrl,
          defaultHeaders
        )
        .pause(5)
    )

  setUp(
    scn
      .inject(
        constantConcurrentUsers(20) during (3 * 60), // 1
        rampConcurrentUsers(20) to props.maxUsers during (3 * 60) // 2
      )
      .protocols(httpProtocol)
  ).maxDuration(props.simulationTimeout * 2)
}
