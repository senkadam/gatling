package io.gatling.core.assertion

import org.scalacheck.{ Arbitrary, Gen }
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{ FlatSpec, Matchers }

trait AssertionGenerator {
  this: AssertionParserSpec =>

  private val doubleGen = Arbitrary.arbitrary[Double]

  private val pathGen = {

    val detailsGen = for (parts <- Gen.nonEmptyListOf(Gen.alphaStr.suchThat(_.size > 0))) yield Details(parts)
    Gen.frequency(50 -> Gen.const(Global), 50 -> detailsGen)
  }

  private val targetGen = {
    val countTargetGen = {
      val countMetricGen = Gen.oneOf(AllRequests, FailedRequests, SuccessfulRequests)
      val countSelectionGen = Gen.oneOf(Count, Percent)

      for (metric <- countMetricGen; selection <- countSelectionGen) yield CountTarget(metric, selection)
    }

    val timeTargetGen = {
      val timeMetricGen = Gen.const(ResponseTime)
      val timeSelectionGen = Gen.oneOf(Min, Max, Mean, StandardDeviation, Percentiles1, Percentiles2)

      for (metric <- timeMetricGen; selection <- timeSelectionGen) yield TimeTarget(metric, selection)
    }

    Gen.oneOf(countTargetGen, timeTargetGen, Gen.const(MeanRequestsPerSecondTarget))
  }

  private val conditionGen = {
    val lessThan = for (d <- doubleGen) yield LessThan(d)
    val greaterThan = for (d <- doubleGen) yield GreaterThan(d)
    val is = for (d <- doubleGen) yield Is(d)
    val between = for (d1 <- doubleGen; d2 <- doubleGen) yield Between(d1, d2)
    val in = for (doubleList <- Gen.nonEmptyListOf(doubleGen)) yield In(doubleList)

    Gen.oneOf(lessThan, greaterThan, is, between, in)
  }

  val assertionGen: Gen[Assertion] = for {
    path <- pathGen
    target <- targetGen
    condition <- conditionGen
  } yield Assertion(path, target, condition)
}
class AssertionParserSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks with AssertionGenerator {

  override implicit val generatorDrivenConfig = PropertyCheckConfig(minSuccessful = 300)

  "The assertion parser" should "be able to parse correctly arbitrary assertions" in {
    forAll(assertionGen) { assertion =>
      val parser = new AssertionParser
      parser.parseAssertion(assertion.serialized.toString) shouldBe assertion
    }
  }
}