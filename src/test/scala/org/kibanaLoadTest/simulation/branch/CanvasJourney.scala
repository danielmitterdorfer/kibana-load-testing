package org.kibanaLoadTest.simulation.branch

import io.gatling.core.Predef._
import io.gatling.core.Predef.{
  constantConcurrentUsers,
  rampConcurrentUsers,
  scenario
}
import io.gatling.core.structure.ScenarioBuilder
import org.kibanaLoadTest.scenario.{Canvas, Login}
import org.kibanaLoadTest.simulation.BaseSimulation

class CanvasJourney extends BaseSimulation {
  val scenarioName = "CanvasJourney"
  props.maxUsers = 200

  val steps = exec(
    Login
      .doLogin(
        appConfig.isSecurityEnabled,
        appConfig.loginPayload,
        appConfig.loginStatusCode
      )
      .pause(5)
  ).exec(Canvas.loadWorkpad(appConfig.baseUrl, defaultHeaders))

  val warmupScn: ScenarioBuilder = scenario("warmup").exec(steps)
  val scn: ScenarioBuilder = scenario(scenarioName).exec(steps)

  setUp(
    warmupScn
      .inject(
        constantConcurrentUsers(20) during (1 * 30),
        rampConcurrentUsers(20) to props.maxUsers during (2 * 60)
      )
      .protocols(httpProtocol)
      .andThen(
        scn
          .inject(
            constantConcurrentUsers(props.maxUsers) during (4 * 60)
          )
          .protocols(httpProtocol)
      )
  ).maxDuration(props.simulationTimeout * 2)
}
