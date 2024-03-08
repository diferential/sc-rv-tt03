`default_nettype none
`timescale 1ns/10ps

module ScrivBB_RSFF   (input  r,
                       input  s,
                       output q);

   wire                       q_out = r ? 1'b0 : (s ? 1'b1 : q);

   // Buffer to make YOSYS happy, otherwise we get combinational loop errors.
`ifdef EMILIAN_ADD_BUFS
   sky130_fd_sc_hd__buf_2 mybuf (.A(q_out), .X(q) );
`else
   assign #0.05 q = q_out;
`endif

endmodule
