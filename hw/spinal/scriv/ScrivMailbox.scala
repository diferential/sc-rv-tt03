package scriv

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.core

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

// val x = new StreamArbiter;

class ScrivMutexArbiter(val portCount : Int) extends Component {
  val io = new Bundle {
    val threads = Vec(slave(new ScrivMutex), portCount)
  }

  val lastGrant = Vec(Reg(Bool()) init (False), portCount);
  // If we still have an ask that was a grant, we'll keep that locked.
  val keepGrant = (io.threads, lastGrant).zipped.map(_.ask & _).reduce(_ | _)
  val newGrant = OHMasking.first(Vec(io.threads.map(_.ask)))

  // io.threads(0).grant := io.threads(0).ask;

  /*
  io.threads.foreach {
    t => t.grant := False
  }
  */

  /*
  io.threads.zipWithIndex.foreach {
    case (t, index) => {
      t.grant := Mux(keepGrant, lastGrant(index), newGrant(index));
    }
  };
  */
}

// RS flip flop blackbox.
// TODO: import blackbox Verilog file.
case class ScrivBB_RSFF() extends BlackBox {
  val io = new Bundle {
    val r = Bool().asInput()
    val s = Bool().asInput()
    val q = out Bool ()
  }

  def idle(): Unit = {
    io.r := False;
    io.s := False;
  }

  def set(): Unit = {
    io.r := False;
    io.s := True;
  }

  def clear(): Unit = {
    io.r := True;
    io.s := False;
  }

  noIoPrefix();
}

case class ScrivMailbox() extends Component {
  val MBOX_BITS = 24;

  val io = new Bundle {
    // TinyTapeout3 interface.
    val tt_in = in Bits (8 bits)
    val tt_out = out Bits (8 bits)

    val out_clk = out Bool ();
    val out_rst = out Bool ();

    val inbox = master(Flow(Bits(MBOX_BITS bits)))
  }

  val masterClock = ClockDomain.internal(
    "masterClock",
    withReset = true,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  );

  // Reset circuit needs all 1s on tt_in, triggers a reset after 7 cycles.
  // Reset is cleared once tt_in reverts from all 1s.
  val resetArea = new ClockingArea(masterClock) {
    val cnt = Reg(UInt(3 bits)) randBoot ();
    val rst_reached = cnt.andR;
    val not_rst_reached = !rst_reached;

    val rst_asserted = io.tt_in === M"1111111-";
    when(rst_asserted) {
      when(not_rst_reached) {
        cnt := cnt + 1;
      }
    } otherwise {
      cnt := 0;
    }
  }

  io.out_rst := resetArea.rst_reached;
  masterClock.reset := resetArea.rst_reached;

  io.out_clk := io.tt_in(0);
  masterClock.clock := io.tt_in(0);

  io.tt_out(0) := !io.tt_in(0)
  io.tt_out(7 downto 1) := B"7'h0";

  val ffSerDataIn = ScrivBB_RSFF();
  val ffSerDataPresent = ScrivBB_RSFF();
  val ffSerDataDone = ScrivBB_RSFF();

  ffSerDataIn.idle();
  ffSerDataPresent.idle();
  ffSerDataDone.idle();

  val outbox_mutex = master(new ScrivMutex);
  outbox_mutex.grant := False
  outbox_mutex.ask := False

  /*
  // TODO: make this part of the port.
  val outbox_mutex2 = master(new ScrivMutex);
  outbox_mutex.ask := False;
  outbox_mutex2.ask := False;

  val arbiter = new ScrivMutexArbiter(2);
  arbiter.io.threads(0).ask := outbox_mutex.ask;
  outbox_mutex.grant := arbiter.io.threads(0).grant;
  arbiter.io.threads(1) <> outbox_mutex2;
  */

  val c = new ClockingArea(masterClock) {
    val state_cnt = Reg(UInt(6 bits)) init 0;
    state_cnt := state_cnt - 1;
    val state_cnt_zero = !state_cnt.orR;

    val fsm = new StateMachine {
      val stateSerialIn = makeInstantEntry();
      val stateChainOutput = new State;
      val stateOutsideOutput = new State;

      val inbox_storage = Reg(Bits(MBOX_BITS bits)) init 0;
      io.inbox.payload := inbox_storage;
      io.inbox.valid := ffSerDataDone.io.q;

      stateSerialIn.whenIsActive {
        // Serial input bit value.
        ffSerDataIn.io.r := io.tt_in(1);
        ffSerDataIn.io.s := io.tt_in(2);

        // Tracks whether we have a new incoming bit.
        ffSerDataPresent.io.r := io.tt_in(3)
        ffSerDataPresent.io.s := (ffSerDataIn.io.r | ffSerDataIn.io.s)

        // Stop bit.. when set the design can flop in the mbox data.
        ffSerDataDone.io.r := io.tt_in(3)
        ffSerDataDone.io.s := io.tt_in(4);

        when(ffSerDataPresent.io.q) {
          inbox_storage := inbox_storage((MBOX_BITS - 1) downto 1) ## ffSerDataIn.io.q;
        }

        val inbox_cnt_shifted = io.inbox.payload(7 downto 4) << 2;
        val jumpCounted = (next: State) => {
          goto(next);
          state_cnt.assignFromBits(inbox_cnt_shifted);
        }

        val cmd = io.inbox.payload(3 downto 0);
        when(io.inbox.valid) {
          switch(cmd) {
            is(B"4'h0") {
              jumpCounted(stateSerialIn)
            }
            is(B"4'h1") {
              blockAddress := io.inbox.payload(7 downto 4);
            }
            is(B"4'h2") {
              jumpCounted(stateChainOutput);
            }
            is(B"4'h3") {
              jumpCounted(stateOutsideOutput);
            }
          }
        }
      } whenIsInactive {
        ffSerDataIn.clear();
        ffSerDataPresent.clear();
        ffSerDataDone.clear();
      }

      // TODO: Do not flop data out if less than X cycles remaining.
      // TODO: Get lock on output.

      val outbox = Reg(Bits(MBOX_BITS bits)) init (0) allowUnsetRegToAvoidLatch;

      stateOutsideOutput.whenIsActive {
        io.tt_out := outbox((MBOX_BITS - 1) downto (MBOX_BITS - 8));
        outbox := outbox.rotateLeft(8);

        // TODO wait for lock
        // TODO exit state machine
        // TODO provide writing capability to outbox register

        when(state_cnt_zero) {
          goto(stateSerialIn);
        }
      }

      val blockAddress = Reg(Bits(4 bits)) init 0;
      // All ones is the last block, all zeros is the first.
      val blockIsLast = blockAddress.andR;
      val blockIsFirst = !blockAddress.orR;

      stateChainOutput.whenIsActive {
        // Output to next block.
        when(blockIsFirst) {
          // First block can be used to clock 0 and then 1 with the two nibbles.
          io.tt_out := B"8'h1";
        } elsewhen (blockIsLast) {
          // Last block keeps zeros for safety to not clock the next design.
          io.tt_out := B"8'h0";
        } otherwise {
          outbox_mutex.ask := True;

          assert(MBOX_BITS % 3 == 0);
          val needCycles = MBOX_BITS / 3;
          assert(needCycles <= 8);
          val haveCycles = state_cnt < 8;

          when(outbox_mutex.grant & haveCycles) {
            // Output two nibbles with 3 useful bits and clock toggle.
            // The controller will shift by 4, push data in, shift by another 4 and clock again.
            val outBits = outbox((MBOX_BITS - 1) downto (MBOX_BITS - 3));
            io.tt_out := outBits ## B"0" ## outBits ## B"1";
            outbox := outbox.rotateLeft(3);
          }
        }

        // TODO read from previous block.

        when(state_cnt_zero) {
          goto(stateSerialIn);
        }
      }

      // TODO(emilian): deal with 8.5 bit new scan chain design.
    }

  } // masterClock
}

case class ScrivTTScanChain() extends Component {
  val io = new Bundle {
    // TinyTapeout3 interface, named after the module directions.
    val tt_in = out Bits (8 bits)
    val tt_out = in Bits (8 bits)

    val tt_sclk_out = out Bool ()
    val tt_sclk_in = in Bool ()

    val tt_data_in = in Bool ()
    val tt_data_out = in Bool ()

    val tt_latch_inputs = in Bool ()
    val tt_scan_local = in Bool ()
  }

  val tt_scan_flops = Bits(9 bits)
  val tt_in_flops = Bits(8 bits)

  io.tt_in := tt_in_flops;
  io.tt_sclk_out := !io.tt_sclk_in;

  when(io.tt_latch_inputs) {
    // For TT, these are latched instead of flopped on CLK, but simulating
    // with a flop for convenience in SpinalHDL.
    tt_in_flops := tt_scan_flops(7 downto 0)
  } elsewhen (io.tt_scan_local) {
    tt_scan_flops(7 downto 0) := io.tt_out;
  } otherwise {
    tt_scan_flops := tt_scan_flops(7 downto 0) ## io.tt_data_in;
  }

  io.tt_data_out := tt_scan_flops(8);
}
