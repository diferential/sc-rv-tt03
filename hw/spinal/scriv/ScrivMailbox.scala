package scriv

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.core

// RS flip flop blackbox.
case class ScrivBB_RSFF() extends BlackBox {
  val io = new Bundle {
    val r = in Bool ()
    val s = in Bool ()
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
  addRTLPath("./hw/verilog/ScrivBlackbox.v")
}

case class ScrivMailbox() extends Component {
  val MBOX_BITS = 28;

  val io = new Bundle {
    // TinyTapeout3 interface.
    val tt_in = in Bits (8 bits)
    val tt_out = out Bits (8 bits)

    val out_clk = out Bool ();
    val out_rst = out Bool ();

    val inbox = master(Flow(Bits(MBOX_BITS bits)))
    val outbox = slave(Stream(Bits(MBOX_BITS bits)))
    val address = out Bits (4 bits);
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
  io.outbox.ready := False;

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
            is(B"4'h0") { /* idle */ }
            is(B"4'h1") { jumpCounted(stateSerialIn) }
            is(B"4'h2") { blockAddress := io.inbox.payload(7 downto 4); }
            is(B"4'h3") { jumpCounted(stateChainOutput); }
            is(B"4'h4") { jumpCounted(stateOutsideOutput); }
          }
        }
      } whenIsInactive {
        ffSerDataIn.clear();
        ffSerDataPresent.clear();
        ffSerDataDone.clear();
      }

      val outbox = Reg(Bits(MBOX_BITS bits)) init (0);
      val outCyclesLeft = Reg(UInt(4 bits)) init 0;

      def outboxDataReadyAndEnoughTimeLeft(cycleWidth: Int): Bool = {
        assert(MBOX_BITS % cycleWidth == 0, s"$MBOX_BITS must be divisible by $cycleWidth");
        val needCycles = MBOX_BITS / cycleWidth;
        io.outbox.valid && state_cnt < needCycles;
      }

      def handleOutbox(cycleWidth: Int, callback: Fragment[Bits] => Area) = new Area {
        val outWord = Fragment(Bits(cycleWidth bits));

        when(outCyclesLeft === 0) {
          when(outboxDataReadyAndEnoughTimeLeft(cycleWidth)) {
            outbox := io.outbox.payload;
            outCyclesLeft := MBOX_BITS / cycleWidth;
            io.outbox.ready := True; // acknowledge data.
          }

          outWord.fragment := 0;
          outWord.last := False;
        } otherwise {
          outWord.fragment := outbox((MBOX_BITS - 1) downto (MBOX_BITS - cycleWidth));
          outWord.last := outCyclesLeft === 1;

          outbox := outbox.rotateLeft(cycleWidth);
        }

        callback(outWord)
      }

      stateOutsideOutput.whenIsActive {
        handleOutbox(
          7,
          word =>
            new Area {
              io.tt_out := word.fragment ## word.last;
            }
        );

        when(state_cnt_zero) {
          goto(stateSerialIn);
        }
      }

      val blockAddress = Reg(Bits(4 bits)) init 0;
      // All ones is the last block, all zeros is the first.
      val blockIsLast = blockAddress.andR;
      val blockIsFirst = !blockAddress.orR;

      io.address := blockAddress;

      def handleInbox(cycleWidth: Int) = new Area {
        // tt_in ends with 2 bits ## fragment.last ## clk.
        inbox_storage := inbox_storage((MBOX_BITS - 1) downto cycleWidth) ## io.tt_in((cycleWidth + 1) downto 2);
        io.inbox.valid := io.tt_in(1);
      }

      stateChainOutput.whenIsActive {
        when(blockIsFirst) {
          // First block can be used to clock 0 and then 1 with the two nibbles.
          io.tt_out := B"8'h1";
        } elsewhen (blockIsLast) {
          // Last block keeps zeros for safety to not clock the next design.
          io.tt_out := B"8'h0";
          handleInbox(2);
        } otherwise {
          handleInbox(2);
          handleOutbox(
            2,
            word =>
              new Area {
                val wordAndLast = word.fragment ## word.last;
                io.tt_out := wordAndLast ## B"0" ## wordAndLast ## B"1";
              }
          );
        }

        when(state_cnt_zero) {
          goto(stateSerialIn);
        }
      }

      /// XXXXXXXXXX ///////////////////////////////////////////
      // TODO(emilian): deal with 8.5 bit new scan chain design.
      /// XXXXXXXXXX ///////////////////////////////////////////
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
