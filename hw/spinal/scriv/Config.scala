package scriv

import spinal.core._
import spinal.core.sim._

object Config {
  def spinal = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      clockEdge = RISING,
      resetActiveLevel = HIGH,
      resetKind = SYNC
    ),
    onlyStdLogicVectorAtTopLevelIo = true,
    genLineComments = true,
    noRandBoot = true
  ).withoutEnumString()

  def sim = SimConfig.withConfig(spinal).withVcdWave.withIVerilog
}
