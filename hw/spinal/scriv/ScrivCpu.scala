package scriv

import spinal.core._
import spinal.lib.toGray

// Hardware definition
case class ScrivCpu() extends Component {
  val io = new Bundle {
    val cond0 = in  Bool()
    val cond1 = in  Bool()
    val flag  = out Bool()
    val state = out UInt(8 bits)
    val state_gray = out Bits(8 bits)
  }

  val counter = Reg(UInt(8 bits)) init 0
  io.state_gray := toGray(counter)

  when(io.cond0) {
    counter := counter + 1
  }

  io.state := counter
  io.flag := (counter === 0) | io.cond1

  // val unusedSignal = UInt(8 bits)
}

object ScrivCpuVerilog extends App {
  Config.spinal.generateVerilog(ScrivCpu()).printPruned()
  Config.spinal.generateVerilog(ScrivMailbox()).printPruned()
}

object ScrivCpuVhdl extends App {
  Config.spinal.generateVhdl(ScrivCpu())
}
