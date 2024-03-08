package scriv

import spinal.core._
import spinal.lib.toGray

// Hardware definition
case class ScrivCpu() extends Component {
  val io = new Bundle {
    // TinyTapeout3 interface.
    val io_in = in Bits (8 bits)
    val io_out = out Bits (8 bits)
  }
  noIoPrefix();

  val mbox = ScrivMailbox();
  mbox.io.tt_in := io.io_in;
  io.io_out := mbox.io.tt_out;

  val masterClock = mbox.masterClock;

  val c = new ClockingArea(masterClock) {
    // sv-rv-tt03-block1 has 24 bits, the rest 16.
    val counter = Reg(UInt(24 bits)) init 0
    val inc_on = Reg(UInt(1 bit)) init 0

    // Always output the counter, informational (cmd = 0).
    mbox.io.outbox.valid := True

    // sc-rv-tt03-2..4 has this
    // mbox.io.outbox.payload := B"8'h0" ## counter ## B"4'h0";
    //
    // sv-rv-tt03-block1 has this.
    mbox.io.outbox.payload := counter ## B"4'h0";

    val cmd = mbox.io.inbox.payload(3 downto 0);
    when(mbox.io.inbox.valid) {
      switch(cmd) {
        is(B"4'ha") { inc_on := mbox.io.inbox.payload(4).asUInt }
        // only sv-rv-tt03-block1 has this.
        is(B"4'hb") { counter := mbox.io.inbox.payload(27 downto 4).asUInt }
        default {
          // only sv-rv-tt03-block1 has this.
          counter := (counter.asBits ^ mbox.io.inbox.payload(27 downto 4)).asUInt;
        }
      }

    } otherwise {
      counter := counter + inc_on;
    }
  }
}

object ScrivCpuVerilog extends App {
  Config.spinal.generateVerilog(ScrivCpu()).printPruned().mergeRTLSource("ScrivBlackbox")
  // Config.spinal.generateVerilog(ScrivMailbox()).printPruned()
}

object ScrivCpuVhdl extends App {
  Config.spinal.generateVhdl(ScrivCpu())
}

///////////////////////////////////////////////////
//// currently unused stuff below
///////////////////////////////////////////////////

/*
class ScrivReg(sz: Int) extends Bundle {
  val value = Reg(Bits(sz bits)) init 0;
}

class ScrivRotatingReg(sz: Int) extends ScrivReg(sz) {
  val low_nibble = value(3 downto 0);
  val high_nibble_in = Bits(4 bits);
  val high_nibble_push = Bool();

  def push(nibble: Bits): Unit = {
    high_nibble_in := nibble;
    high_nibble_push := True;
  }

  def pop(nibble: Bits): Bits = {
    high_nibble_push := True;
    low_nibble;
  }
}

class ScrivMutex extends Bundle with IMasterSlave {
  val ask = Bool ();
  val grant = Bool ();

  override def asMaster(): Unit = {
    out(ask)
    in(grant)
  }
}

class ScrivMutexArbiter(val portCount : Int) extends Component {
  val io = new Bundle {
    val threads = Vec(slave(new ScrivMutex), portCount)
  }

  val lastGrant = Vec(Reg(Bool()) init (False), portCount);
  // If we still have an ask that was a grant, we'll keep that locked.
  val keepGrant = (io.threads, lastGrant).zipped.map(_.ask & _).reduce(_ | _)
  val newGrant = OHMasking.first(Vec(io.threads.map(_.ask)))

  io.threads.zipWithIndex.foreach {
    case (t, index) => {
      t.grant := Mux(keepGrant, lastGrant(index), newGrant(index));
    }
  };
}

 */
